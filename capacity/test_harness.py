import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile


def load(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


class HarnessTest(unittest.TestCase):
    def test_ooxml_padding_preserves_entries_and_exact_byte_boundaries(self):
        data=io.BytesIO()
        with zipfile.ZipFile(data,'w') as archive: archive.writestr('word/document.xml','<document>隔离</document>')
        with tempfile.TemporaryDirectory() as root:
            path=Path(root)/'sample.docx';load('boundaries').padded_ooxml(data.getvalue(),path,128*1024)
            self.assertEqual(path.stat().st_size,128*1024)
            with zipfile.ZipFile(path) as archive: self.assertIn('隔离',archive.read('word/document.xml').decode())

    def summary(self, root, passed=True, attempts=100, busy=0, cpu='10%'):
        stage={'name':'read-open-10','profile':'read','model':'open','level':10,'duration':'5m','thresholdPass':passed}
        (root/'stages.json').write_text(json.dumps([stage]))
        (root/'containers.ndjson').write_text(json.dumps({'Name':'aird-capacity-test-generator','CPUPerc':cpu})+'\n')
        (root/'read-open-10.json').write_text(json.dumps({'metrics':{
            'business_attempted':{'values':{'count':attempts}},'business_completed':{'values':{'count':attempts-busy}},
            'busy_rejected':{'values':{'count':busy}},'dropped_iterations':{'values':{'count':0}}}}))
        return load('report').report(root)

    def test_fast_busy_rejections_never_establish_capacity(self):
        with tempfile.TemporaryDirectory() as directory:
            result=self.summary(Path(directory),passed=False,busy=90)
            self.assertEqual(result['groups']['read/open']['highestStableLevel'],0)
            self.assertEqual(result['stages'][0]['busyRejectionRate'],0.9)

    def test_generator_saturation_cannot_establish_server_capacity(self):
        with tempfile.TemporaryDirectory() as directory:
            result=self.summary(Path(directory),cpu='98%')
            self.assertFalse(result['stages'][0]['accepted'])
            self.assertIsNone(result['groups']['read/open']['firstFailedLevel'])

    def test_warmup_time_is_excluded_from_completed_request_rate(self):
        with tempfile.TemporaryDirectory() as directory:
            result=self.summary(Path(directory),attempts=300)
            self.assertEqual(result['stages'][0]['actualCompletedRps'],1)
            self.assertFalse(result['capacityAccepted'])

    def test_empty_load_is_not_a_capacity_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            result=self.summary(Path(directory),attempts=0)
            self.assertFalse(result['stages'][0]['accepted'])


if __name__=='__main__':unittest.main()
