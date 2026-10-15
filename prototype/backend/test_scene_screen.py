import tempfile
import unittest
from pathlib import Path

from PIL import Image, ImageDraw

from scene_screen import near_duplicate, pixels, matching_source
from server import Service
from test_scene_memory import SCENE
import test_server


class ImageScreenTests(unittest.TestCase):
    def test_local_object_change_is_not_a_duplicate(self):
        with tempfile.TemporaryDirectory() as folder:
            first=Path(folder)/'first.jpg'
            second=Path(folder)/'second.jpg'
            image=Image.new('RGB',(160,120),'gray')
            image.save(first,quality=90)
            ImageDraw.Draw(image).rectangle((55,35,95,75),fill='red')
            image.save(second,quality=90)
            self.assertTrue(near_duplicate(pixels(first),pixels(first)))
            self.assertFalse(near_duplicate(pixels(first),pixels(second)))


class AutomaticSceneTests(unittest.TestCase):
    setUp=test_server.MemoryTests.setUp
    tearDown=test_server.MemoryTests.tearDown
    metadata=test_server.MemoryTests.metadata

    def test_automatic_duplicate_skips_model_but_manual_rechecks(self):
        calls=[]
        def model(*_):
            calls.append(1)
            return SCENE
        service=Service(self.store,{'memory_mode':'scene'},{},model=model,screen=matching_source)
        first=service.observe(self.metadata(1) | {'capture_mode':'automatic'},self.image)
        second=service.observe(self.metadata(2) | {'capture_mode':'automatic'},self.image)
        self.assertEqual(first[1]['outcome'],'saved')
        self.assertEqual(second[1]['outcome'],'skipped_duplicate')
        self.assertEqual(second,service.observe(self.metadata(2) | {'capture_mode':'automatic'},self.image))
        self.assertEqual(second[1]['duplicate_of'],first[1]['scene_memory']['id'])
        self.assertEqual(len(calls),1)
        self.assertEqual(len(self.store.scenes()),1)
        manual=service.observe(self.metadata(3) | {'capture_mode':'manual'},self.image)
        self.assertEqual(manual[1]['outcome'],'saved')
        self.assertEqual(len(calls),2)


if __name__=='__main__': unittest.main()
