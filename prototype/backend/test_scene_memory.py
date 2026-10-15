import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from scene_memory import answer_question, validate_scene, candidates, last_seen_request, hot_answer
from server import Service, Fault
import test_server

SCENE=dict(summary='A red mug beside a laptop.',place='desk',objects=['red mug left of laptop'],activities=[],visible_text=[],uncertainties=[],view_quality='clear')
STRUCTURED=SCENE | {'entities':[{'label':'mug','appearance':'red ceramic mug','relation':'left of laptop'}]}

class SceneTests(unittest.TestCase):
    setUp=test_server.MemoryTests.setUp
    tearDown=test_server.MemoryTests.tearDown
    metadata=test_server.MemoryTests.metadata
    def test_scene_store_deduplicates_and_survives_new_session(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:SCENE)
        first=service.observe(self.metadata(),self.image)
        self.assertEqual(first,service.observe(self.metadata(),self.image))
        self.assertEqual(len(self.store.scenes()),1)
        self.store.new_run('scene-v1')
        self.assertEqual(len(self.store.scenes()),1)
        self.assertEqual(first[1]['scene_memory']['summary'],SCENE['summary'])
    def test_closed_session_cannot_store_scene(self):
        self.store.admit(self.metadata(),self.image)
        self.store.new_run('new')
        self.assertEqual(self.store.complete_scene(self.metadata(),SCENE)[0],410)
        self.assertEqual(self.store.scenes(),[])
    def test_invalid_scene_never_stored(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:{'summary':'invented'})
        self.assertEqual(service.observe(self.metadata(),self.image)[0],502)
        self.assertEqual(self.store.scenes(),[])
    def test_scene_needs_no_enrollment(self):
        self.assertTrue(Service(self.store,{'memory_mode':'scene'},{}).setup()['ready'])
    def test_structured_sighting_has_source_and_survives_restart(self):
        service=Service(self.store,{'memory_mode':'scene','model':'test-model'},{},model=lambda *a:STRUCTURED)
        service.observe(self.metadata(),self.image)
        sighting=self.store.hot_sighting('mug')
        self.assertEqual(sighting['source_id'],self.store.scenes()[0]['id'])
        self.assertEqual(sighting['relation'],'left of laptop')
        self.assertEqual(self.store.db.execute('SELECT model_version FROM scene_observations').fetchone()[0],'test-model')
        self.store.db.close()
        from server import Store
        self.store=Store(self.temp.name)
        self.assertEqual(self.store.hot_sighting('mug')['source_id'],sighting['source_id'])
        self.assertEqual(len(self.store.scenes()),1)
    def test_uncertain_view_and_duplicate_objects_do_not_enter_fast_answer(self):
        uncertain=STRUCTURED | {'view_quality':'uncertain'}
        Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:uncertain).observe(self.metadata(),self.image)
        self.assertIsNone(self.store.hot_sighting('mug'))
        multiple=STRUCTURED | {'entities':STRUCTURED['entities']*2}
        Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:multiple).observe(self.metadata(2),self.image)
        self.assertIsNone(self.store.hot_sighting('mug'))
    def test_late_arriving_older_photo_does_not_replace_newest_sighting(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:STRUCTURED)
        newer=self.metadata() | {'captured_at':'2026-09-23T12:00:00Z'}
        service.observe(newer,self.image)
        newest=self.store.hot_sighting('mug')['source_id']
        older=self.metadata(2) | {'captured_at':'2026-09-22T12:00:00Z'}
        service.observe(older,self.image)
        self.assertEqual(self.store.hot_sighting('mug')['source_id'],newest)
    def test_scene_mode_accepts_queued_capture_after_newer_sequence(self):
        service=Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:STRUCTURED)
        newer=self.metadata(2) | {'captured_at':'2026-09-23T12:00:00Z'}
        older=self.metadata(1) | {'captured_at':'2026-09-22T12:00:00Z'}
        self.assertEqual(service.observe(newer,self.image)[0],200)
        self.assertEqual(service.observe(older,self.image)[0],200)
        self.assertEqual(len(self.store.scenes()),2)
        self.assertEqual(self.store.hot_sighting('mug')['captured_at'],newer['captured_at'])
    def test_legacy_scene_remains_readable_without_entity_index(self):
        Service(self.store,{'memory_mode':'scene'},{},model=lambda *a:SCENE).observe(self.metadata(),self.image)
        self.assertEqual(len(self.store.scenes()),1)
        self.assertIsNone(self.store.hot_sighting('mug'))

class AnswerTests(unittest.TestCase):
    def rows(self):
        return [dict(id='a',captured_at='2026-09-22T10:00:00Z',time_quality='approximate',scene=SCENE,image='unused.jpg')]
    def test_empty_memory_does_not_call_provider(self):
        result=answer_question([], 'Where are my keys?',{},caller=lambda *a:self.fail('No model call needed'))
        self.assertTrue(result['insufficient_evidence']);self.assertEqual(result['evidence_ids'],[])
    def test_fast_answer_is_historical_and_identity_qualified(self):
        self.assertEqual(last_seen_request('Where did I last see my mug?')[0],'mug')
        self.assertIsNone(last_seen_request('What changed in the kitchen?'))
        sighting=dict(source_id='a',captured_at='2026-09-22T10:00:00Z',time_quality='approximate',
                      appearance='red',relation='left of laptop',place='desk')
        result=hot_answer('Where is my mug?',sighting,4)
        self.assertEqual(result['route'],'hot_index')
        self.assertEqual(result['evidence_ids'],['a'])
        self.assertTrue(result['insufficient_evidence'])
        self.assertIn('not a confirmed current location',result['answer'])
        self.assertIn('does not establish that it is yours',result['answer'])
    def test_invalid_retrieval_id_rejected(self):
        with self.assertRaises(ValueError):answer_question(self.rows(),'question',{},caller=lambda *a:{'evidence_ids':['invented']})
    @patch('scene_memory.image_part',return_value={'type':'text','text':'test image'})
    def test_uncited_claim_forces_unknown(self,_):
        replies=iter([{'evidence_ids':['a']},{'answer':'Your keys are in the car','evidence_ids':[],'insufficient_evidence':False}])
        result=answer_question(self.rows(),'keys?',{},caller=lambda *a:next(replies))
        self.assertTrue(result['insufficient_evidence']);self.assertNotIn('car',result['answer'])
    @patch('scene_memory.image_part',return_value={'type':'text','text':'test image'})
    def test_citation_must_be_selected_evidence(self,_):
        replies=iter([{'evidence_ids':['a']},{'answer':'A mug','evidence_ids':['wrong'],'insufficient_evidence':False}])
        with self.assertRaises(ValueError):answer_question(self.rows(),'mug?',{},caller=lambda *a:next(replies))
    @patch('scene_memory.image_part',return_value={'type':'text','text':'test image'})
    def test_supported_answer_returns_original_capture_evidence(self,_):
        replies=iter([{'evidence_ids':['a']},{'answer':'A red mug was beside the laptop in the recorded view.','evidence_ids':['a'],'insufficient_evidence':False}])
        result=answer_question(self.rows(),'What was on the desk?',{},caller=lambda *a:next(replies))
        self.assertEqual(result['evidence_ids'],['a']);self.assertEqual(result['total_count'],1)

if __name__=='__main__':unittest.main()
