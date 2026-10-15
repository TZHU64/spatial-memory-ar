import http.client
import json
import tempfile
import threading
import unittest
from desktop import start_desktop
from server import Store,Service
import test_server
from test_scene_memory import STRUCTURED

class DesktopTests(unittest.TestCase):
    def test_loopback_ui_and_origin_guards(self):
        with tempfile.TemporaryDirectory() as folder:
            store=Store(folder)
            server=start_desktop(Service(store,{'memory_mode':'scene'},{}),port=0)
            try:
                conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
                conn.request('GET','/');r=conn.getresponse();self.assertEqual(r.status,200);self.assertIn(b'Ask memory',r.read())
                conn.request('GET','/api/memories',headers={'Host':'attacker.example'});r=conn.getresponse();self.assertEqual(r.status,403);r.read()
                headers={'Content-Type':'application/json','X-Spatial-UI':'1'}
                conn.request('POST','/api/ask',json.dumps({'question':'Where are my keys?'}),headers | {'Origin':'https://attacker.example'})
                r=conn.getresponse();self.assertEqual(r.status,403);r.read()
                conn.request('POST','/api/ask',json.dumps({'question':'Where are my keys?'}),headers)
                r=conn.getresponse();self.assertEqual(r.status,200);data=json.loads(r.read());self.assertTrue(data['insufficient_evidence']);self.assertEqual(data['evidence'],[])
                conn.request('GET','/api/image/../../config.local.json');r=conn.getresponse();self.assertEqual(r.status,404);r.read()
                conn.close()
            finally:
                server.shutdown();server.server_close();store.db.close()

class FastRouteTests(unittest.TestCase):
    setUp=test_server.MemoryTests.setUp
    tearDown=test_server.MemoryTests.tearDown
    metadata=test_server.MemoryTests.metadata
    def test_last_seen_uses_index_without_provider_request(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:STRUCTURED)
        service.observe(self.metadata(),self.image)
        server=start_desktop(service,port=0)
        try:
            server.ask_lock.acquire()
            conn=http.client.HTTPConnection('127.0.0.1',server.server_port,timeout=5)
            conn.request('POST','/api/ask',json.dumps({'question':'Where did I last see my mug?'}),
                         {'Content-Type':'application/json','X-Spatial-UI':'1'})
            response=conn.getresponse()
            self.assertEqual(response.status,200)
            data=json.loads(response.read())
            self.assertEqual(data['route'],'hot_index')
            self.assertEqual(len(data['evidence']),1)
            self.assertEqual(data['evidence_ids'],[data['evidence'][0]['id']])
            self.assertNotIn('image',data['evidence'][0])
            conn.close()
        finally:
            server.ask_lock.release()
            server.shutdown();server.server_close()

if __name__=='__main__':unittest.main()
