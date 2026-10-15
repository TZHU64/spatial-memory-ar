import unittest
from mimo import validate_proposal, outcome

class ProposalTests(unittest.TestCase):
    def proposal(self, **changes):
        return dict(target_presence='present', identity='match', location_id='desk_01', view_quality='clear', **{}) | changes

    def test_unknown_location_positive_does_not_inherit_location(self):
        value = self.proposal(location_id=None)
        self.assertEqual(outcome(value), 'saved')
        self.assertIsNone(value['location_id'])

    def test_unclear_presence_is_not_saved(self):
        self.assertEqual(outcome(self.proposal(view_quality='uncertain')), 'uncertain')
        self.assertEqual(outcome(self.proposal(identity='ambiguous')), 'uncertain')

    def test_only_explicit_clear_nondetection_counts(self):
        value = self.proposal(target_presence='not_detected', identity='unknown', location_id=None)
        self.assertEqual(outcome(value), 'not_detected')
        self.assertEqual(outcome(value | {'view_quality': 'uncertain'}), 'uncertain')

    def test_missing_or_injected_fields_are_rejected(self):
        value = self.proposal()
        del value['view_quality']
        for invalid in [value, self.proposal(captured_at='invented'), self.proposal(location_id='bed'),
                        self.proposal(target_presence='not_detected')]:
            with self.assertRaises(ValueError): validate_proposal(invalid)

if __name__ == '__main__': unittest.main()
