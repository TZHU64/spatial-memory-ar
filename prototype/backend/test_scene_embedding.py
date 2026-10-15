import tempfile
import unittest
from pathlib import Path
from PIL import Image
from scene_embedding import MobileCLIPScreen, compare
from server import Service
from test_scene_memory import SCENE
import test_server


def feature(a,b=0): return [[a,b] for _ in range(5)]


class EmbeddingTests(unittest.TestCase):
    def test_exact_duplicate_and_changed_region(self):
        ref=feature(1)
        self.assertEqual(compare(ref,[('saved',ref)]),('saved',0,0))
        local=feature(1);local[4]=[0,1]
        _,g,r=compare(local,[('saved',ref)])
        self.assertEqual(g,0);self.assertEqual(r,1)

    def test_cache_and_only_retained_images_form_bank(self):
        with tempfile.TemporaryDirectory() as directory:
            first=Path(directory)/'a.jpg';second=Path(directory)/'b.jpg'
            Image.new('RGB',(4,4),'red').save(first);Image.new('RGB',(4,4),'blue').save(second)
            calls=[]
            def encoder(path): calls.append(path);return feature(1)
            screen=MobileCLIPScreen(encoder=encoder)
            self.assertIsNone(screen(first,[]))
            self.assertIsNone(screen(first,[])) # Cached features alone must never create a retained reference.
            self.assertEqual(screen(second,[('evidence',first)]),'evidence')
            self.assertEqual(len(calls),2)
            screen.close()


class ServiceEmbeddingTests(unittest.TestCase):
    setUp=test_server.MemoryTests.setUp
    tearDown=test_server.MemoryTests.tearDown
    metadata=test_server.MemoryTests.metadata

    def test_local_model_failure_prevents_online_call_and_is_terminal(self):
        def failed(*_): raise RuntimeError('missing local weights')
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *_:self.fail('No VLM call'),screen=failed)
        metadata=self.metadata()|{'capture_mode':'automatic'}
        response=service.observe(metadata,self.image)
        self.assertEqual(response[0],503)
        self.assertEqual(response[1]['code'],'local_vision_failed')
        self.assertEqual(service.observe(metadata,self.image),response)
        self.assertEqual(self.store.scene_count(),0)
        self.assertEqual(len(list(Path(self.temp.name).glob('*.jpg'))),1)

    def test_manual_capture_bypasses_local_screen(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *_:SCENE,
            screen=lambda *_:self.fail('Manual capture must bypass S3'))
        self.assertEqual(service.observe(self.metadata()|{'capture_mode':'manual'},self.image)[1]['outcome'],'saved')

    def test_rgb_duplicate_suppresses_vlm_with_source_link(self):
        calls=[]
        screen=MobileCLIPScreen(encoder=lambda _:feature(1))
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *_:(calls.append(1) or SCENE),screen=screen)
        first=service.observe(self.metadata()|{'capture_mode':'automatic'},self.image)
        second=service.observe(self.metadata(2)|{'capture_mode':'automatic'},self.image)
        self.assertEqual(second[1]['outcome'],'skipped_duplicate')
        self.assertEqual(second[1]['duplicate_of'],first[1]['scene_memory']['id'])
        self.assertEqual(len(calls),1)
