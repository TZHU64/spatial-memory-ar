import hashlib
import http.client
import json
import io
import tempfile
import threading
import time
import unittest
from pathlib import Path
from PIL import Image
from server import Store, Service, Fault, validate, Handler, ThreadingHTTPServer

class MemoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(self.temp.name)
        self.sid = self.store.new_run('test')
        data = io.BytesIO(); Image.new('RGB',(32,32),'red').save(data,format='JPEG'); self.image = data.getvalue()
        self.positive = dict(target_presence='present',identity='match',view_quality='clear',location_id='desk_01')
    def tearDown(self):
        self.store.db.close(); self.temp.cleanup()
    def metadata(self, seq=1):
        return dict(schema_version='0.6', session_id=self.sid, request_id='r'+str(seq), capture_sequence=seq,
                    captured_at='2026-09-22T12:00:00Z', time_quality='approximate', sensor_timestamp_ns='123', image_sha256=hashlib.sha256(self.image).hexdigest())
    def service(self, proposal=None, **kwargs):
        return Service(self.store, {}, {}, model=lambda *args: proposal or self.positive, **kwargs)
    def test_deduplication_and_conflicts(self):
        calls=[]
        service=self.service(); service.model=lambda *args: calls.append(1) or self.positive
        first=service.observe(self.metadata(),self.image)
        self.assertEqual(first,service.observe(self.metadata(),self.image)); self.assertEqual(len(calls),1)
        validate('observe_response',first[1])
        with self.assertRaises(Fault): service.observe(self.metadata() | {'captured_at':'2026-09-22T13:00:00Z'},self.image)
    def test_nondetection_preserves_history_unknown_location_does_not_inherit(self):
        self.service().observe(self.metadata(),self.image)
        negative=dict(target_presence='not_detected',identity='unknown',view_quality='clear',location_id=None)
        self.service(negative).observe(self.metadata(2),self.image)
        self.assertEqual(self.store.memory(self.sid)['sighting']['location_id'],'desk_01')
        self.service(self.positive | {'location_id':None}).observe(self.metadata(3),self.image)
        self.assertIsNone(self.store.memory(self.sid)['sighting']['location_id'])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM sightings').fetchone()[0],2)
    def test_old_session_reply_never_enters_new_run(self):
        self.store.admit(self.metadata(),self.image)
        new=self.store.new_run('new')
        status, result=self.store.complete(self.metadata(),self.positive)
        self.assertEqual(status,410); self.assertIsNone(self.store.memory(new)['sighting'])
    def test_timeout_is_terminal_and_late_worker_cannot_write(self):
        release=threading.Event(); done=threading.Event()
        def delayed(*args):
            release.wait(1); done.set(); return self.positive
        service=self.service(deadline=.02); service.model=delayed
        first=service.observe(self.metadata(),self.image)
        self.assertEqual(first[0],502)
        release.set(); done.wait(1)
        self.assertIsNone(self.store.memory(self.sid)['sighting'])
        self.assertEqual(first,service.observe(self.metadata(),self.image))
    def test_busy_and_recall_while_inference(self):
        self.store.admit(self.metadata(),self.image)
        self.assertIsNone(self.store.memory(self.sid)['sighting'])
        with self.assertRaises(Fault) as error: self.store.admit(self.metadata(2),self.image)
        self.assertEqual(error.exception.code,'busy')
    def test_invalid_image_or_metadata_never_reaches_model(self):
        with self.assertRaises(Fault): self.service().observe(self.metadata(),b'invalid')
        with self.assertRaises(Fault): self.service().observe(self.metadata() | {'captured_at':'bad'},self.image)
        with self.assertRaises(Fault): self.service().observe(self.metadata() | {'capture_mode':'walking'},self.image)
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM requests').fetchone()[0],0)
    def test_three_reference_steps_start_run(self):
        config={'references':{k:k+'.jpg' for k in ('phone_01','desk_01','shelf_01')}}
        service=Service(self.store,config,{})
        service.config_directory=Path(self.temp.name)
        self.assertEqual(service.setup()['next_reference'],'phone_01')
        with self.assertRaises(Fault): service.enroll('shelf_01',self.image)
        for label in ('phone_01','desk_01','shelf_01'): service.enroll(label,self.image)
        self.assertTrue(service.setup()['ready'])
        self.assertNotEqual(self.store.session()['session_id'],self.sid)

class HttpTests(unittest.TestCase):
    setUp = MemoryTests.setUp
    tearDown = MemoryTests.tearDown
    metadata = MemoryTests.metadata
    service = MemoryTests.service
    def test_authenticated_multipart_roundtrip(self):
        service = self.service()
        service.config = {'pairing_token':'test-only'}
        server = ThreadingHTTPServer(('127.0.0.1',0), Handler)
        server.service = service
        threading.Thread(target=server.serve_forever,daemon=True).start()
        try:
            connection = http.client.HTTPConnection('127.0.0.1', server.server_port, timeout=5)
            connection.request('GET','/v1/session')
            response=connection.getresponse(); self.assertEqual(response.status,401); response.read()
            headers={'Authorization':'Bearer test-only'}
            connection.request('GET','/v1/session',headers=headers)
            response=connection.getresponse(); self.assertEqual(response.status,200)
            self.assertEqual(json.loads(response.read())['session_id'],self.sid)
            boundary='test-boundary'
            body=(f'--{boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n'+json.dumps(self.metadata())+'\r\n').encode()
            body+=(f'--{boundary}\r\nContent-Disposition: form-data; name="image"; filename="test.jpg"\r\nContent-Type: image/jpeg\r\n\r\n').encode()+self.image+f'\r\n--{boundary}--\r\n'.encode()
            connection.request('POST','/v1/observe',body,headers | {'Content-Type':'multipart/form-data; boundary='+boundary})
            response=connection.getresponse(); self.assertEqual(response.status,200)
            self.assertEqual(json.loads(response.read())['outcome'],'saved')
            request=dict(schema_version='0.6', session_id=self.sid, request_id='recall')
            connection.request('POST','/v1/recall',json.dumps(request),headers | {'Content-Type':'application/json'})
            response=connection.getresponse(); self.assertEqual(response.status,200)
            self.assertEqual(json.loads(response.read())['memory']['sighting']['location_id'],'desk_01')
            connection.close()
        finally:
            server.shutdown(); server.server_close()

if __name__=='__main__': unittest.main()
