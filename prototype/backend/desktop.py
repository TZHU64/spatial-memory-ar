"""Local computer UI. Separate loopback listener; no provider/pairing keys in HTML."""
import json
import re
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse
from scene_memory import answer_question, last_seen_request, hot_answer

ROOT=Path(__file__).resolve().parent

class DesktopHandler(BaseHTTPRequestHandler):
    def log_message(self,*_): pass
    def send(self,code,body,kind='application/json'):
        if isinstance(body,(dict,list)): body=json.dumps(body).encode()
        self.send_response(code)
        self.send_header('Content-Type',kind)
        self.send_header('Content-Length',str(len(body)))
        self.send_header('Cache-Control','no-store')
        self.send_header('X-Content-Type-Options','nosniff')
        self.send_header('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
        self.end_headers()
        try: self.wfile.write(body)
        except (BrokenPipeError,ConnectionResetError): pass
    def permitted(self,write=False):
        host=self.headers.get('Host','')
        allowed={f'127.0.0.1:{self.server.server_port}',f'localhost:{self.server.server_port}'}
        if host not in allowed: return False
        origin=self.headers.get('Origin')
        if origin and origin != 'http://'+host: return False
        if self.headers.get('Sec-Fetch-Site') == 'cross-site': return False
        return not write or (self.headers.get('X-Spatial-UI') == '1' and self.headers.get('Content-Type') == 'application/json')
    def public(self,row):
        return {k:v for k,v in row.items() if k not in ('image','session_id','request_id')}
    def do_GET(self):
        if not self.permitted(): return self.send(403,{'error':'Open this interface on the computer.'})
        path=urlparse(self.path).path
        files={'/':('index.html','text/html; charset=utf-8'),'/app.js':('app.js','text/javascript; charset=utf-8'),'/style.css':('style.css','text/css; charset=utf-8')}
        if path in files:
            name,kind=files[path]; return self.send(200,(ROOT/'web'/name).read_bytes(),kind)
        if path == '/api/memories':
            rows=self.server.service.store.scenes()
            return self.send(200,{'memories':[self.public(r) for r in rows], 'count':len(rows)})
        match=re.fullmatch('/api/image/([0-9a-f]{32})',path)
        if match:
            row=next((r for r in self.server.service.store.scenes() if r['id']==match[1]),None)
            if row:
                try: return self.send(200,Path(row['image']).read_bytes(),'image/jpeg')
                except OSError: pass
        self.send(404,{'error':'Not found'})
    def do_POST(self):
        if not self.permitted(write=True): return self.send(403,{'error':'Request not allowed'})
        if self.path != '/api/ask': return self.send(404,{'error':'Not found'})
        try:
            length=int(self.headers.get('Content-Length','0'))
            if not 0 < length <= 16384: raise ValueError()
            self.connection.settimeout(10)
            data=json.loads(self.rfile.read(length))
            question=data.get('question')
            if not isinstance(question,str) or not 1 <= len(question.strip()) <= 2000: raise ValueError()
        except (ValueError,OSError,AttributeError): return self.send(400,{'error':'Enter a question of up to 2,000 characters.'})
        store=self.server.service.store
        request=last_seen_request(question)
        sighting=store.hot_sighting(request[0]) if request else None
        result=hot_answer(question,sighting,store.scene_count()) if sighting else None
        if result is not None:
            row=store.scene_by_id(result['evidence_ids'][0])
            if row is not None:
                result['evidence']=[self.public(row)]
                return self.send(200,result)
        if not self.server.ask_lock.acquire(blocking=False): return self.send(409,{'error':'A question is already being answered. Please wait.'})
        try:
            rows=store.scenes()
            result=answer_question(rows,question.strip(),self.server.service.config)
            ids=set(result['evidence_ids'])
            result['evidence']=[self.public(r) for r in rows if r['id'] in ids]
            self.send(200,result)
        except Exception:
            self.send(502,{'error':'Could not answer from memory. Please try again. Saved observations are unchanged.'})
        finally: self.server.ask_lock.release()

def start_desktop(service,port=8766):
    server=ThreadingHTTPServer(('127.0.0.1',port),DesktopHandler)
    server.daemon_threads=True
    server.service=service
    server.ask_lock=threading.Lock()
    threading.Thread(target=server.serve_forever,daemon=True).start()
    print(f'Ask memory on this computer: http://127.0.0.1:{server.server_port}',flush=True)
    return server
