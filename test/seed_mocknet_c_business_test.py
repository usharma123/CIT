"""Pure checks for the bounded Approach C demo seed."""

import importlib.util
from pathlib import Path
import tempfile
import sys
import unittest
from unittest.mock import patch


SEED = Path(__file__).resolve().parents[1] / 'script/seed-mocknet-c-business.py'
sys.path.insert(0, str(SEED.parent))
from mocknet_c_demo import latest_seed, pin_dashboard, pin_links, seed_window
spec = importlib.util.spec_from_file_location('seed_mocknet_c_business', SEED)
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)
STREAM = Path(__file__).resolve().parents[1] / 'script/mocknet-stream.py'
stream_spec = importlib.util.spec_from_file_location('mocknet_stream', STREAM)
stream = importlib.util.module_from_spec(stream_spec)
stream_spec.loader.exec_module(stream)


class SeedPlanTests(unittest.TestCase):
    def test_completed_seed_has_a_fixed_window_and_links(self):
        manifest = {'state': 'verified', 'startedAt': '2026-09-01T12:00:00+00:00',
                    'finishedAt': '2026-09-01T12:01:04+00:00',
                    'scenarios': [{'operationId': 'existing-operation'}]}
        window = seed_window(manifest)
        self.assertEqual(int(window['to']) - int(window['from']), 304000)
        pin_links(manifest)
        self.assertNotIn('now', manifest['dashboardUrl'])
        self.assertIn('var-overview_from=', manifest['scenarios'][0]['processUrl'])
        dashboard = {'templating': {'list': [{'name': 'overview_from'}, {'name': 'overview_to'}]}}
        pin_dashboard(dashboard, manifest)
        self.assertEqual(dashboard['refresh'], '')
        self.assertEqual(dashboard['time']['from'], '2026-09-01T11:58:00+00:00')
        self.assertEqual(dashboard['templating']['list'][1]['current']['value'], window['to'])

    def test_repeat_seed_reuses_verified_run_without_http_admission(self):
        with tempfile.TemporaryDirectory() as scratch:
            state = Path(scratch)
            (state / 'seeds').mkdir()
            manifest = {'state': 'verified', 'startedAt': '2026-09-01T12:00:00+00:00',
                        'finishedAt': '2026-09-01T12:01:04+00:00', 'scenarios': []}
            seed.save_manifest(state / 'seeds/001.json', manifest)
            seed.save_manifest(state / 'seeds/002.json', {'state': 'incomplete'})
            self.assertEqual(latest_seed(state)[0].name, '001.json')
            with patch.object(seed, 'STATE', state), patch.object(sys, 'argv', ['seed']), \
                 patch.object(seed, 'check_target') as target, patch.object(seed, 'admit') as admit, \
                 patch.object(seed.subprocess, 'run') as build:
                seed.main()
            target.assert_not_called()
            admit.assert_not_called()
            build.assert_called_once()

    def test_default_plan_is_bounded_unique_and_chronological(self):
        rows = seed.scenarios('TEST', 40)
        self.assertEqual(len(rows), 91)
        self.assertEqual(len({row['tradeId'] for row in rows}), len(rows))
        self.assertEqual([row['delaySeconds'] for row in rows],
                         sorted(row['delaySeconds'] for row in rows))
        self.assertEqual(max(row['delaySeconds'] for row in rows), 64)
        self.assertEqual(sum('Routine pair' in row['label'] for row in rows), 80)
        with self.assertRaises(ValueError):
            seed.scenarios('TEST', 61)

    def test_only_local_demo_profile_can_seed(self):
        seed.require_local_demo('local-demo', 'java --spring.profiles.active=observability-demo')
        for mode, command in [('production', 'java --spring.profiles.active=observability-demo'),
                              ('local-demo', 'java --spring.profiles.active=production')]:
            with self.assertRaises(RuntimeError):
                seed.require_local_demo(mode, command)

    def test_xml_escapes_generated_fields(self):
        body = seed.trade_xml('A&B', 'M<1', 'BUY>1', 'SELL\"2')
        self.assertIn('<tradeId>A&amp;B</tradeId>', body)
        self.assertIn('<messageId>M&lt;1</messageId>', body)
        self.assertIn('<partyId>BUY&gt;1</partyId>', body)

    def test_direct_c_feed_checks_the_workspace_jvm_profile(self):
        with tempfile.TemporaryDirectory() as scratch:
            state = Path(scratch)
            (state / 'app.pid').write_text('12345')
            with patch.object(stream, 'STATE', state):
                demo = f'java -jar {state / "app.jar"} --spring.profiles.active=observability-demo'
                with patch.object(stream.subprocess, 'check_output', return_value=demo):
                    stream.require_c_demo_jvm()
                production = f'java -jar {state / "app.jar"} --spring.profiles.active=production'
                with patch.object(stream.subprocess, 'check_output', return_value=production):
                    with self.assertRaises(SystemExit):
                        stream.require_c_demo_jvm()
                (state / 'app.pid').unlink()
                with self.assertRaises(SystemExit):
                    stream.require_c_demo_jvm()


if __name__ == '__main__':
    unittest.main()
