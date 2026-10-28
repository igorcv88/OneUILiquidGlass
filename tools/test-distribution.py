#!/usr/bin/env python3
"""Host contracts for the SfLive bundle, single-APK publication and restricted Android entry points."""
import pathlib,re,unittest,xml.etree.ElementTree as ET
ROOT=pathlib.Path(__file__).resolve().parents[1]
class DistributionTests(unittest.TestCase):
    def test_release_uploads_apk_only_and_builds_live_bundle_before_apk(self):
        workflow=(ROOT/'.github/workflows/release.yml').read_text()
        publish=workflow.split('gh release create "$RELEASE_TAG"',1)[1].split('--verify-tag',1)[0]
        self.assertEqual(publish.count('"$RELEASE_ASSET"'),1)
        for obsolete in ('MODULE_ASSET','LIVE_ASSET','.sha256'):self.assertNotIn(obsolete,publish)
        self.assertLess(workflow.index('Build SurfaceFlinger live bundle'),workflow.index('Test, lint and assemble release APK'))
        self.assertIn('assets/sfhook/',workflow)
        for file in ('oulg_inject','liboulg_sf.so','oulg-live.sh','allowed_fingerprints','live.prop'):self.assertIn(file,workflow)
        self.assertIn('build/apk-assets',(ROOT/'app/build.gradle').read_text())
    def test_manifest_keeps_scope_and_actions_restricted(self):
        manifest=ET.parse(ROOT/'app/src/main/AndroidManifest.xml');ns='{http://schemas.android.com/apk/res/android}'
        receivers={r.get(ns+'name'):r for r in manifest.findall('application/receiver')}
        self.assertEqual(receivers['.TestNotificationReceiver'].get(ns+'exported'),'false')
        self.assertEqual(receivers['.ConfigReadReceiver'].get(ns+'permission'),'android.permission.STATUS_BAR')
        self.assertNotIn('android.permission.INTERNET',[p.get(ns+'name') for p in manifest.findall('uses-permission')])
    def test_helper_owned_mode_cannot_shadow_injector(self):
        repository=(ROOT/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config/ConfigRepository.java').read_text()
        client=(ROOT/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config/ConfigClient.java').read_text()
        self.assertIn('!spec.helperOwned()',repository);self.assertIn('!spec.helperOwned()',client)
        self.assertFalse((ROOT/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/modules/RootOperations.java').exists())
    def test_registry_has_exact_active_reader_coverage(self):
        java=(ROOT/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/glass/Tuning.java').read_text()
        c=(ROOT/'sfhook/oulg_sf.c').read_text();registry=(ROOT/'app/src/main/java/io/github/igorcv88/oneuiliquidglass/config/SettingsRegistry.java').read_text()
        readers=set(re.findall(r'(?:number|prop)\("([a-z0-9.]+)"',java))|set(re.findall(r'debug\.oulg\.([a-z0-9.]+)',c))
        specs=set(re.findall(r'add\(new SettingSpec\("([a-z0-9.]+)"',registry))
        self.assertEqual(109,len(readers));self.assertEqual(readers,specs)
if __name__=='__main__':unittest.main()
