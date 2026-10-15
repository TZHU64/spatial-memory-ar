import copy
import json
from pathlib import Path
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request

import numpy as np

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
from gate import NoveltyGate, grid_difference
from evaluate import score
from recording import Recording, Stamp
from review_server import make_server

CONFIG=json.loads((Path(__file__).resolve().parents[1]/"gate_config.json").read_text())


def features(axis=0):
    value=np.zeros((5,8),np.float32)
    value[:,axis]=1
    return value


class GateTests(unittest.TestCase):
    def config(self):
        return dict(CONFIG,global_novelty=.14,region_novelty=.24,min_global_for_region=.035,
                    persistence_seconds=.35,persistence_samples=3)

    def test_scene_change_must_persist_and_familiar_scene_stays_quiet(self):
        gate=NoveltyGate(self.config());a=features()
        self.assertFalse(gate.observe(a,0).propose)
        self.assertFalse(gate.observe(a,200_000_000).propose)
        self.assertTrue(gate.observe(a,400_000_000).propose)
        gate.commit(a)
        for t in range(1,100): self.assertFalse(gate.observe(a,t*1_000_000_000).propose)
        b=features(1)
        self.assertFalse(gate.observe(b,101_000_000_000).propose)
        self.assertFalse(gate.observe(b,101_200_000_000).propose)
        self.assertTrue(gate.observe(b,101_400_000_000).propose)

    def test_small_embedding_changes_do_not_create_repeated_captures(self):
        gate=NoveltyGate(self.config());gate.commit(features())
        b=features();b[:,0]=.999;b[:,1]=.02;b/=np.linalg.norm(b,axis=1,keepdims=True)
        self.assertEqual(gate.observe(b,1_000_000_000).reason,"familiar_view")

    def test_local_new_region_survives_global_similarity(self):
        gate=NoveltyGate(self.config());gate.commit(features())
        b=features();b[0,0]=.95;b[0,1]=np.sqrt(1-.95**2);b[1]=features(1)[1]
        for t in (0,200_000_000): self.assertFalse(gate.observe(b,t).propose)
        decision=gate.observe(b,400_000_000)
        self.assertTrue(decision.propose)
        self.assertEqual(decision.reason,"new_region_signature")

    def test_future_frames_cannot_change_a_past_decision(self):
        sequence=[features(0)]*4+[features(1)]*4+[features(2)]*4
        def run(values):
            gate=NoveltyGate(self.config());result=[]
            for i,feature in enumerate(values):
                decision=gate.observe(feature,i*200_000_000);result.append(decision)
                if decision.propose: gate.commit(feature)
            return result
        self.assertEqual(run(sequence[:7]),run(sequence)[:7])

    def test_prototype_memory_is_bounded(self):
        gate=NoveltyGate(dict(self.config(),prototype_limit=3))
        for i in range(8): gate.commit(features(i))
        self.assertEqual(len(gate.bank),3)
        self.assertEqual(int(np.argmax(gate.bank[0][0])),5)

    def test_exposure_gain_is_not_pixel_novelty(self):
        grid=np.linspace(.2,.6,768,dtype=np.float32).reshape(24,32)
        a,b=grid_difference(grid,grid*1.2+.05)
        self.assertLess(a,1e-6);self.assertLess(b,1e-6)


class TimingAndEvaluationTests(unittest.TestCase):
    def test_rgb_pair_is_future_and_within_shared_interval(self):
        recording=object.__new__(Recording)
        recording.rgb_times=[100,200,300]
        recording.frames={"rgb":[Stamp(i,t,t,t,t) for i,t in enumerate(recording.rgb_times)]}
        recording.end_ns=250
        self.assertEqual(recording.first_rgb_after(151,.001).session_ns,200)
        self.assertIsNone(recording.first_rgb_after(251,.001))
        self.assertIsNone(recording.first_rgb_after(101,.00001))

    def test_unlabeled_walk_never_reports_accuracy(self):
        result=score([],[],0,10)
        self.assertIsNone(result["event_recall"])
        self.assertIsNone(result["capture_precision"])

    def test_capture_must_fall_inside_visible_event(self):
        events=[{"start":2,"end":3,"importance":"required","label":"new room"}]
        missed=score(events,[{"session_seconds":3.1}],0,10)
        hit=score(events,[{"session_seconds":2.5}],0,10)
        self.assertEqual(missed["event_recall"],0)
        self.assertEqual(hit["event_recall"],1)
        self.assertIsNone(hit["capture_precision"])
        with self.assertRaises(ValueError): score([dict(events[0],end=11)],[],0,10)


class ReviewTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        (self.root/"vga.mp4").write_bytes(b"0123456789")
        self.report={"run_id":"test","source_folder":str(self.root),"task":"new places",
                     "interval":[1,4],"policies":{"layered":[{"session_seconds":2.5}]}}
        (self.root/"report.json").write_text(json.dumps(self.report))
        self.annotations=self.root/"evaluator/events.json"
        self.server=make_server(self.root,self.annotations,0)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.url=f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown();self.server.server_close();self.thread.join();self.temp.cleanup()

    def test_video_range_and_path_boundaries(self):
        request=urllib.request.Request(self.url+"/media/vga.mp4",headers={"Range":"bytes=2-5"})
        with urllib.request.urlopen(request) as response:
            self.assertEqual(response.status,206);self.assertEqual(response.read(),b"2345")
        for path,headers in [("/assets/../report.json",{}),("/media/vga.mp4",{"Host":"untrusted.example"})]:
            with self.assertRaises(urllib.error.HTTPError):
                urllib.request.urlopen(urllib.request.Request(self.url+path,headers=headers))

    def test_labels_are_separate_and_reject_out_of_interval_data(self):
        document={"run_id":"test","review_complete":True,"events":[{"label":"new room","importance":"required","start":2,"end":3}]}
        def save(value):
            return urllib.request.urlopen(urllib.request.Request(self.url+"/api/annotations",data=json.dumps(value).encode(),headers={"Content-Type":"application/json"}))
        with save(document) as response:
            result=json.load(response);self.assertEqual(result["metrics"]["layered"]["event_recall"],1)
            self.assertEqual(result["metrics"]["layered"]["capture_precision"],1)
        self.assertTrue(self.annotations.is_file())
        invalid=copy.deepcopy(document);invalid["events"][0]["end"]=5
        with self.assertRaises(urllib.error.HTTPError): save(invalid)
        self.assertEqual(json.loads(self.annotations.read_text())["events"][0]["end"],3)


if __name__=="__main__": unittest.main()
