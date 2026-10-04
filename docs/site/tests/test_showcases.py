"""Showcase source/navigation consistency; never evaluates a calculation or creates expected amounts."""
import json
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[3]
EXPECTED={'de-est','ifrs-impairment','cost-accounting','ifrs-income-taxes','fixed-assets',
          'ifrs-leases','de-gewst','circular-calculation','energy-budget','project-portfolio'}

class ShowcaseMetadataTest(unittest.TestCase):
    def test_catalog_covers_every_app_once_with_real_confined_sources_and_manifest(self):
        apps=json.loads((ROOT/'docs/site/applications.json').read_text())
        actual={path.name for path in (ROOT/'apps').iterdir() if (path/'README.md').is_file()}
        self.assertEqual(len(apps),len(EXPECTED))
        self.assertEqual({app['id'] for app in apps},EXPECTED)
        self.assertEqual(actual,EXPECTED)
        for app in apps:
            directory=ROOT/'apps'/app['id']
            for name in [app['schema'],app['case'],app['verification'],'manifest.json',
                         *[source['path'] for source in app['source_documents']]]:
                with self.subTest(app=app['id'],source=name):
                    target=(directory/name).resolve()
                    self.assertTrue(target.is_relative_to(directory.resolve()))
                    self.assertTrue(target.is_file())
            self.assertGreaterEqual(len(app['source_documents']),2)
            self.assertTrue(app['tutorial'].endswith('.html') or '.html#' in app['tutorial'])

    def test_new_domain_tutorials_have_actual_anchors_and_preserve_scope(self):
        text=(ROOT/'docs/site/neutral-domains.md').read_text()
        self.assertIn('## Monthly energy',text)
        self.assertIn('## Supplied project selection',text)
        self.assertIn('EUR 31.84',text)
        self.assertIn('1.733333',text)
        self.assertEqual(text.count('**Showcase only.**'),2)
        self.assertIn('does not optimize',text)
        self.assertIn('engine output',text)

    def test_package_and_source_pages_distinguish_authority_evidence_and_publication(self):
        package=(ROOT/'docs/site/packages.md').read_text()
        source=(ROOT/'docs/site/independent-sources.md').read_text()
        self.assertIn('STRICT_HANDLES',package)
        self.assertIn('rename-and-restore',package)
        self.assertIn('same permissions',package)
        self.assertIn('[valid-from, valid-until)',package)
        self.assertIn('does not imply arbitrary ZIP',package)
        self.assertIn('local staging',package)
        self.assertIn('does not run calculations',' '.join(source.split()))
        for name in EXPECTED:
            self.assertIn(f'repo:apps/{name}/',source)

if __name__=='__main__':
    unittest.main()
