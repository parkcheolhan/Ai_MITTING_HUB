import sys
from pathlib import Path
from types import SimpleNamespace
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'main/resources/speech'))
from diarize import assign_speakers

def turn(speaker,start,end):
    return SimpleNamespace(speaker=speaker,start=start,end=end)

class VoiceMatchingTest(unittest.TestCase):
    def test_gap_matches_voice_not_nearest_speaker_or_text(self):
        turns=[turn(0,0,2),turn(1,10,12)]
        vectors={(0,2):[1,0],(10,12):[0,1],(8,9):[1,0]}
        result=assign_speakers([{'start':0,'end':2,'text':'첫 화자'}, {'start':10,'end':12,'text':'둘째 화자'}, {'start':8,'end':9,'text':'둘째 화자라고 말해도 목소리는 첫 화자'}],turns,lambda a,b:vectors.get((a,b)))
        self.assertEqual(result[0]['speaker'],result[2]['speaker'])
        self.assertNotEqual(result[1]['speaker'],result[2]['speaker'])
        self.assertEqual(result[2]['speakerAssignment'],'VOICE_MATCH')

    def test_no_turns_cluster_similar_voice_and_keep_numbered_estimates(self):
        segments=[{'start':0,'end':2,'text':'처음'}, {'start':3,'end':5,'text':'같은 목소리'}, {'start':6,'end':8,'text':'다른 목소리'}, {'start':9,'end':10,'text':'작은 음성'}]
        vectors={(0,2):[1,0],(3,5):[1,.01],(6,8):[0,1]}
        result=assign_speakers(segments,[],lambda a,b:vectors.get((a,b)))
        self.assertEqual(result[0]['speaker'],result[1]['speaker'])
        self.assertNotEqual(result[0]['speaker'],result[2]['speaker'])
        self.assertEqual(result[-1]['speakerAssignment'],'ESTIMATED')
        self.assertTrue(all('미지정' not in s['speaker'] for s in result))

    def test_speaker_limit_merges_nearest_voice_prototypes_and_blocks_extra_clusters(self):
        turns=[turn(0,0,2),turn(1,3,5),turn(2,6,8)]
        vectors={(0,2):[1,0],(3,5):[1,.01],(6,8):[0,1],(9,11):[-1,0]}
        segments=[{'start':a,'end':b,'text':'발언'}for a,b in vectors]
        result=assign_speakers(segments,turns,lambda a,b:vectors.get((a,b)),max_speakers=2)
        self.assertEqual(result[0]['speaker'],result[1]['speaker'])
        self.assertNotEqual(result[0]['speaker'],result[2]['speaker'])
        self.assertLessEqual(len({s['speaker'] for s in result}),2)

    def test_single_speaker_limit_also_applies_without_detected_turns(self):
        vectors={(0,2):[1,0],(3,5):[0,1],(6,8):[-1,0]}
        segments=[{'start':a,'end':b,'text':'발언'}for a,b in vectors]
        result=assign_speakers(segments,[],lambda a,b:vectors.get((a,b)),max_speakers=1)
        self.assertEqual(len({s['speaker'] for s in result}),1)

if __name__=='__main__':unittest.main()
