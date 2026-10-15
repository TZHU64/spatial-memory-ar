"""Loopback-only prototype service. Use adb reverse for glasses access."""
import argparse
import hashlib
import hmac
import io
import json
import secrets
import re
import sqlite3
import threading
import time
import uuid
from datetime import datetime, timezone
from email.parser import BytesParser
from email.policy import default
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image
from jsonschema import Draft202012Validator, FormatChecker
from mimo import recognize, outcome
from scene_memory import describe_scene, validate_scene
from scene_screen import matching_source
from scene_embedding import MobileCLIPScreen

ROOT = Path(__file__).resolve().parent
SCHEMA = json.loads((ROOT.parent / 'shared/protocol/minimal-demo.schema.json').read_text())

class Fault(Exception):
    def __init__(self, code, message, status=400):
        super().__init__(message)
        self.code, self.status = code, status

def validate(kind, value):
    schema = {'$ref': '#/$defs/' + kind, '$defs': SCHEMA['$defs']}
    if not Draft202012Validator(schema, format_checker=FormatChecker()).is_valid(value):
        raise Fault('invalid_request', 'Request does not match protocol 0.6')
    if kind == 'observe_request':
        stamp = value['captured_at']
        try:
            if not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})', stamp):
                raise ValueError()
            datetime.fromisoformat(stamp.replace('Z','+00:00'))
        except ValueError:
            raise Fault('invalid_request', 'Capture time must be a valid timestamp') from None

def verify_jpeg(data):
    if len(data) > 8 * 1024 * 1024:
        raise Fault('invalid_image', 'JPEG exceeds 8 MiB')
    try:
        with Image.open(io.BytesIO(data)) as image:
            if image.format != 'JPEG' or image.width * image.height > 24_000_000:
                raise ValueError()
            image.load()
    except Exception:
        raise Fault('invalid_image', 'A decodable JPEG is required') from None

class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.db = sqlite3.connect(self.directory / 'memory.sqlite3', check_same_thread=False)
        self.db.executescript('''
        CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, enrollment TEXT, active INTEGER);
        CREATE TABLE IF NOT EXISTS requests(session TEXT, id TEXT, seq INTEGER, digest TEXT,
          metadata TEXT, image TEXT, response TEXT, status INTEGER,
          PRIMARY KEY(session,id), UNIQUE(session,seq));
        CREATE TABLE IF NOT EXISTS sightings(session TEXT, seq INTEGER, value TEXT, PRIMARY KEY(session,seq));
        CREATE TABLE IF NOT EXISTS scene_memories(id TEXT PRIMARY KEY, session TEXT, request TEXT,
          captured_at TEXT, time_quality TEXT, image TEXT, scene TEXT, UNIQUE(session,request));
        CREATE TABLE IF NOT EXISTS scene_observations(id TEXT PRIMARY KEY, source_id TEXT NOT NULL,
          captured_at TEXT NOT NULL, captured_sort REAL NOT NULL, time_quality TEXT NOT NULL,
          label TEXT NOT NULL, appearance TEXT NOT NULL, relation TEXT NOT NULL,
          place TEXT NOT NULL, view_quality TEXT NOT NULL, model_version TEXT NOT NULL,
          FOREIGN KEY(source_id) REFERENCES scene_memories(id));
        CREATE INDEX IF NOT EXISTS scene_observations_hot
          ON scene_observations(label, captured_sort DESC);
        ''')
        self.db.commit()

    def new_run(self, enrollment):
        with self.lock, self.db:
            self.db.execute('UPDATE sessions SET active=0')
            sid = uuid.uuid4().hex
            self.db.execute('INSERT INTO sessions VALUES(?,?,1)', (sid, enrollment))
            return sid

    def session(self):
        with self.lock:
            row = self.db.execute('SELECT id,enrollment FROM sessions WHERE active=1').fetchone()
            if not row:
                raise Fault('no_active_session', 'Enroll references and start a run on the computer', 404)
            return dict(schema_version='0.6', session_id=row[0], enrollment_version=row[1])

    def check_active(self, sid):
        row = self.db.execute('SELECT active FROM sessions WHERE id=?', (sid,)).fetchone()
        if not row or not row[0]:
            raise Fault('closed_session', 'Run is closed. Select Start / Resume.', 410)

    def memory(self, sid):
        with self.lock:
            self.check_active(sid)
            row = self.db.execute('SELECT value FROM sightings WHERE session=? ORDER BY seq DESC LIMIT 1', (sid,)).fetchone()
            return dict(evidence_status='historical' if row else 'unknown', sighting=json.loads(row[0]) if row else None)

    def admit(self, metadata, image, allow_late=False):
        validate('observe_request', metadata)
        verify_jpeg(image)
        if hashlib.sha256(image).hexdigest() != metadata['image_sha256']:
            raise Fault('invalid_image', 'JPEG hash mismatch')
        canonical = json.dumps(metadata, sort_keys=True)
        digest = hashlib.sha256(canonical.encode() + image).hexdigest()
        sid, rid, seq = metadata['session_id'], metadata['request_id'], metadata['capture_sequence']
        with self.lock, self.db:
            self.check_active(sid)
            row = self.db.execute('SELECT digest,response,status FROM requests WHERE session=? AND id=?', (sid,rid)).fetchone()
            if row:
                if row[0] != digest:
                    raise Fault('id_conflict', 'Request ID reused with different content', 409)
                if row[1] is None:
                    raise Fault('request_pending', 'This request is still running', 409)
                return None, (row[2], json.loads(row[1]))
            if self.db.execute('SELECT 1 FROM requests WHERE session=? AND response IS NULL', (sid,)).fetchone():
                raise Fault('busy', 'An observation is already running', 409)
            maximum = self.db.execute('SELECT MAX(seq) FROM requests WHERE session=?', (sid,)).fetchone()[0]
            if not allow_late and maximum is not None and seq <= maximum:
                raise Fault('id_conflict', 'Capture sequence must increase; start a new run after restarting the app', 409)
            # File names are generated locally; request IDs never become paths.
            file = self.directory / (uuid.uuid4().hex + '.jpg')
            file.write_bytes(image)
            self.db.execute('INSERT INTO requests VALUES(?,?,?,?,?,?,NULL,NULL)', (sid,rid,seq,digest,canonical,str(file)))
            return file, None

    def scenes(self):
        with self.lock:
            rows = self.db.execute('SELECT id,session,request,captured_at,time_quality,image,scene FROM scene_memories ORDER BY captured_at DESC,id DESC').fetchall()
            return [dict(id=r[0],session_id=r[1],request_id=r[2],captured_at=r[3],time_quality=r[4],image=r[5],scene=json.loads(r[6])) for r in rows]

    def scene_count(self):
        with self.lock:
            return self.db.execute('SELECT COUNT(*) FROM scene_memories').fetchone()[0]

    def recent_scene_images(self, limit=12):
        with self.lock:
            return self.db.execute('''SELECT id,image FROM scene_memories
                ORDER BY captured_at DESC,id DESC LIMIT ?''', (limit,)).fetchall()

    def scene_by_id(self, identifier):
        with self.lock:
            row = self.db.execute('''SELECT id,session,request,captured_at,time_quality,image,scene
                FROM scene_memories WHERE id=?''', (identifier,)).fetchone()
            return (dict(id=row[0],session_id=row[1],request_id=row[2],captured_at=row[3],
                         time_quality=row[4],image=row[5],scene=json.loads(row[6])) if row else None)

    def hot_sighting(self, label):
        """Newest clear class sighting; never treats a class as a verified individual."""
        with self.lock:
            rows = self.db.execute('''SELECT source_id,captured_at,time_quality,appearance,relation,place,captured_sort
                FROM scene_observations WHERE label=? AND view_quality='clear'
                ORDER BY captured_sort DESC,id DESC LIMIT 2''', (label.casefold(),)).fetchall()
            if not rows or (len(rows) > 1 and rows[0][6] == rows[1][6]):
                return None  # Tied times or multiple same-class objects are ambiguous.
            row = rows[0]
            return dict(source_id=row[0],captured_at=row[1],time_quality=row[2],
                        appearance=row[3],relation=row[4],place=row[5],label=label)

    def complete_scene(self, metadata, proposal=None, failure=None, model_version='unknown'):
        if failure:
            return self.complete(metadata, failure=failure)
        validate_scene(proposal)
        sid, rid = metadata['session_id'], metadata['request_id']
        with self.lock, self.db:
            row = self.db.execute('SELECT response,status,image FROM requests WHERE session=? AND id=?',(sid,rid)).fetchone()
            if row[0] is not None:
                return row[1], json.loads(row[0])
            try:
                self.check_active(sid)
            except Fault as error:
                return self.complete(metadata,failure=error)
            identifier = uuid.uuid4().hex
            self.db.execute('INSERT INTO scene_memories VALUES(?,?,?,?,?,?,?)',
                (identifier,sid,rid,metadata['captured_at'],metadata['time_quality'],row[2],json.dumps(proposal)))
            captured_sort = datetime.fromisoformat(metadata['captured_at'].replace('Z','+00:00')).timestamp()
            for entity in proposal.get('entities', []):
                self.db.execute('''INSERT INTO scene_observations VALUES(?,?,?,?,?,?,?,?,?,?,?)''',
                    (uuid.uuid4().hex,identifier,metadata['captured_at'],captured_sort,
                     metadata['time_quality'],entity['label'].strip().casefold(),
                     entity['appearance'],entity['relation'],proposal['place'],
                     proposal['view_quality'],model_version))
            # Keep the older glasses wire shape usable; extra scene fields drive newer clients.
            result = dict(schema_version='0.6',session_id=sid,request_id=rid,
                outcome='saved',notice='Scene remembered. Ask on your computer.',
                memory=self.memory(sid),scene_memory=dict(id=identifier,captured_at=metadata['captured_at'],**proposal))
            self.db.execute('UPDATE requests SET response=?,status=200 WHERE session=? AND id=?',(json.dumps(result),sid,rid))
            return 200,result

    def complete_duplicate(self, metadata, source_id):
        sid, rid = metadata['session_id'], metadata['request_id']
        with self.lock, self.db:
            row = self.db.execute('SELECT response,status,image FROM requests WHERE session=? AND id=?',(sid,rid)).fetchone()
            if row[0] is not None:
                return row[1], json.loads(row[0])
            try:
                self.check_active(sid)
            except Fault as error:
                return self.complete(metadata, failure=error)
            result = dict(schema_version='0.6',session_id=sid,request_id=rid,
                outcome='skipped_duplicate',notice='Similar saved view; no model analysis.',
                memory=self.memory(sid),duplicate_of=source_id)
            self.db.execute('UPDATE requests SET response=?,status=200 WHERE session=? AND id=?',
                            (json.dumps(result),sid,rid))
            image_path = row[2]
        try:
            Path(image_path).unlink(missing_ok=True)
        except OSError:
            pass  # A failed cleanup cannot undo the terminal deduplicated response.
        return 200,result

    def complete(self, metadata, proposal=None, failure=None):
        sid, rid = metadata['session_id'], metadata['request_id']
        with self.lock, self.db:
            row = self.db.execute('SELECT response,status FROM requests WHERE session=? AND id=?', (sid,rid)).fetchone()
            if row and row[0] is not None:
                return row[1], json.loads(row[0])
            try:
                self.check_active(sid)
            except Fault as error:
                failure = error
            base = dict(schema_version='0.6', session_id=sid, request_id=rid)
            if failure:
                result = dict(base, code=failure.code, message=str(failure))
                status = failure.status
            else:
                kind = outcome(proposal)
                if kind == 'saved':
                    sighting = dict(observation_id=uuid.uuid4().hex, source_request_id=rid,
                        capture_sequence=metadata['capture_sequence'], object_id='phone_01',
                        location_id=proposal['location_id'], captured_at=metadata['captured_at'], time_quality=metadata['time_quality'])
                    self.db.execute('INSERT INTO sightings VALUES(?,?,?)', (sid,metadata['capture_sequence'],json.dumps(sighting)))
                notices = {'saved': 'Sighting saved', 'not_detected': 'Phone not found in this view', 'uncertain': 'Could not identify the phone clearly'}
                result = dict(base, outcome=kind, notice=notices[kind], memory=self.memory(sid))
                status = 200
            self.db.execute('UPDATE requests SET response=?,status=? WHERE session=? AND id=?', (json.dumps(result),status,sid,rid))
            return status, result

class Service:
    def __init__(self, store, config, references, model=recognize, deadline=30, screen=None):
        self.store, self.config, self.references = store, config, references
        self.model, self.deadline = model, deadline
        self.scene_mode = config.get("memory_mode") == "scene"
        if self.scene_mode and model is recognize:
            self.model = describe_scene
        mode = config.get('rgb_gate', 'mobileclip2')
        if mode not in ('mobileclip2', 'pixels'):
            raise ValueError('Unknown RGB gate')
        self.screen = screen if screen is not None else (MobileCLIPScreen() if mode == 'mobileclip2' else matching_source)
        self.enrollment_lock = threading.RLock()
        self.config_directory = ROOT

    def setup(self):
        if self.scene_mode:
            return dict(next_reference=None,ready=True,memory_mode="scene")
        with self.enrollment_lock:
            for label in ('phone_01','desk_01','shelf_01'):
                path = self.config_directory / self.config['references'][label]
                try:
                    verify_jpeg(path.read_bytes())
                except (OSError, Fault):
                    return dict(next_reference=label, ready=False)
            return dict(next_reference=None, ready=True)

    def enroll(self, label, image):
        verify_jpeg(image)
        with self.enrollment_lock:
            expected = self.setup()['next_reference']
            if expected is None or label != expected:
                raise Fault('invalid_request', 'Reference step changed. Select Start / Resume.')
            path = self.config_directory / self.config['references'][label]
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(image)
            state = self.setup()
            if state['ready']:
                self.references, enrollment = load_references(self.config, self.config_directory)
                self.store.new_run(enrollment)
            return state

    def observe(self, metadata, image):
        path, cached = self.store.admit(metadata, image, allow_late=self.scene_mode)
        if cached:
            return cached
        if self.scene_mode and metadata.get('capture_mode') == 'automatic':
            try:
                duplicate = self.screen(path, self.store.recent_scene_images(limit=64))
            except Exception:
                return self.store.complete_scene(metadata, failure=Fault('local_vision_failed',
                    'Local RGB check unavailable. Photo retained; automatic model upload stopped.', 503))
            if duplicate is not None:
                return self.store.complete_duplicate(metadata, duplicate)
        if self.scene_mode:
            finish = lambda metadata, **kwargs: self.store.complete_scene(
                metadata, model_version=self.config.get('model','mimo-v2.6-flash'), **kwargs)
        else:
            finish = self.store.complete
        ready = threading.Event()
        box = {}
        # The worker never writes memory. Only this deadline owner can finalize a request.
        def infer():
            try:
                box['proposal'] = self.model(path, self.references, self.config)
            except Exception:
                box['failure'] = Fault('model_failed', 'MiMo could not check this image. Memory unchanged.', 502)
            finally:
                box['finished_at'] = time.monotonic()
                ready.set()
        end = time.monotonic() + self.deadline
        threading.Thread(target=infer, daemon=True).start()
        if not ready.wait(max(0, end-time.monotonic())) or box.get('finished_at', end+1) > end:
            return finish(metadata, failure=Fault('model_failed', 'MiMo timed out. Memory unchanged.', 502))
        try:
            return finish(metadata, proposal=box.get('proposal'), failure=box.get('failure'))
        except ValueError:
            return finish(metadata, failure=Fault('model_failed', 'Invalid model output. Memory unchanged.', 502))

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log credentials, images or provider responses.

    def reply(self, status, value):
        body = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def dispatch(self):
        metadata = {}
        try:
            expected = 'Bearer ' + self.server.service.config['pairing_token']
            if not hmac.compare_digest(self.headers.get('Authorization', '').encode(), expected.encode()):
                raise Fault('unauthenticated', 'Pairing token required', 401)
            store = self.server.service.store
            if self.command == 'GET' and self.path == '/v1/setup':
                return self.reply(200, self.server.service.setup())
            if self.command == 'GET' and self.path == '/v1/session':
                return self.reply(200, store.session())
            if self.command != 'POST' or self.path not in ('/v1/observe', '/v1/recall', '/v1/enroll'):
                raise Fault('invalid_request', 'Unknown endpoint', 404)
            try:
                size = int(self.headers.get('Content-Length', '0'))
            except ValueError:
                raise Fault('invalid_request', 'Invalid body length')
            limit = 9*1024*1024 if self.path in ('/v1/observe','/v1/enroll') else 65536
            if not 0 < size <= limit:
                raise Fault('invalid_request', 'Body length outside allowed limit')
            self.connection.settimeout(10)
            body = self.rfile.read(size)
            if len(body) != size:
                raise Fault('invalid_request', 'Incomplete body')
            if self.path == '/v1/recall':
                metadata = json.loads(body)
                validate('recall_request', metadata)
                return self.reply(200, dict(metadata, memory=store.memory(metadata['session_id'])))
            header = self.headers.get('Content-Type', '')
            if not header.startswith('multipart/form-data;'):
                raise Fault('invalid_request', 'Multipart metadata and image required')
            message = BytesParser(policy=default).parsebytes(('Content-Type: '+header+'\r\nMIME-Version: 1.0\r\n\r\n').encode()+body)
            parts = {}
            for part in message.iter_parts():
                name = part.get_param('name', header='content-disposition')
                if name in parts or name not in ('metadata','image'):
                    raise Fault('invalid_request', 'Unexpected multipart field')
                parts[name] = part.get_payload(decode=True)
            if set(parts) != {'metadata','image'} or len(parts['metadata']) > 65536:
                raise Fault('invalid_request', 'Invalid multipart fields')
            metadata = json.loads(parts['metadata'])
            if self.path == '/v1/enroll':
                if not isinstance(metadata, dict) or set(metadata) != {'label'}:
                    raise Fault('invalid_request', 'Reference label required')
                return self.reply(200, self.server.service.enroll(metadata['label'], parts['image']))
            status, result = self.server.service.observe(metadata, parts['image'])
            self.reply(status, result)
        except Fault as error:
            trusted = metadata if isinstance(metadata,dict) else {}
            self.reply(error.status, dict(schema_version='0.6', session_id=trusted.get('session_id'), request_id=trusted.get('request_id'), code=error.code, message=str(error)))
        except (ValueError, TypeError, KeyError, OSError):
            self.reply(400, dict(schema_version='0.6', session_id=None, request_id=None, code='invalid_request', message='Could not read request'))

    do_GET = dispatch
    do_POST = dispatch


def load_references(config, directory):
    refs = {label: (directory / path).resolve() for label,path in config['references'].items()}
    if set(refs) != {'phone_01','desk_01','shelf_01'}:
        raise ValueError('Three labeled references required')
    digest = hashlib.sha256()
    for label, path in sorted(refs.items()):
        data = path.read_bytes()
        verify_jpeg(data)
        digest.update(label.encode()+data)
    return refs, digest.hexdigest()[:24]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, default=ROOT/'config.local.json')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--host', default='127.0.0.1', help='Computer LAN address for Wi-Fi, or loopback for USB')
    args = parser.parse_args()
    config = json.loads(args.config.read_text())
    if not config.get('pairing_token'):
        config['pairing_token'] = secrets.token_urlsafe(32)
        args.config.write_text(json.dumps(config,indent=2)+'\n')
        args.config.chmod(0o600)
    store = Store(ROOT/'runtime')
    refs = {}
    if config.get('memory_mode') == 'scene':
        try:
            store.session()  # Preserve the capture session and history across ordinary restarts.
        except Fault:
            store.new_run('scene-v1')
        print('General scene memory ready. No object enrollment required.',flush=True)
    else:
        try:
            refs, enrollment = load_references(config, args.config.parent)
            store.new_run(enrollment)
            print('New run ready. Select Start / Resume on the glasses.', flush=True)
        except (OSError, ValueError, Fault):
            with store.lock, store.db:
                store.db.execute('UPDATE sessions SET active=0')
            print('Waiting for phone/desk/shelf reference photos.', flush=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.service = Service(store, config, refs)
    server.service.config_directory = args.config.parent.resolve()
    if isinstance(server.service.screen, MobileCLIPScreen) and server.service.scene_mode:
        server.service.screen.prepare()
    print(f'Memory service listening on {args.host}:{args.port}.', flush=True)
    from desktop import start_desktop
    start_desktop(server.service)
    server.serve_forever()

if __name__ == '__main__':
    main()
