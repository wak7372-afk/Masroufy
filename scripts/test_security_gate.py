"""Regression tests for fail-closed release decisions, without building an APK."""
import hashlib
from pathlib import Path
import tempfile
import os
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import security_gate as gate


class ReviewedLintPolicyTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar=Path(self.temp.name)/'bcpkix-jdk15to18-1.86.jar'
        self.jar.write_bytes(b'reviewed test fixture, never a production JAR')
        self.issue=ET.Element('issue',id='TrustAllX509TrustManager',category='Security')
        ET.SubElement(self.issue,'location',file=str(self.jar))

    def test_unreviewed_binary_never_allowed(self):
        self.assertFalse(gate.reviewed_lint_issue(self.issue,True,True))

    def test_missing_binary_never_allowed(self):
        self.jar.unlink()
        self.assertFalse(gate.reviewed_lint_issue(self.issue,True,True))

    def test_exact_review_requires_offline_and_removed_code(self):
        digest=hashlib.sha256(self.jar.read_bytes()).hexdigest()
        with patch.object(gate,'BCPKIX_SHA256',digest):
            self.assertTrue(gate.reviewed_lint_issue(self.issue,True,True))
            self.assertFalse(gate.reviewed_lint_issue(self.issue,False,True))
            self.assertFalse(gate.reviewed_lint_issue(self.issue,True,False))
            self.assertFalse(gate.reviewed_lint_issue(self.issue,False,False))

    def test_unrelated_warning_and_extra_location_fail(self):
        digest=hashlib.sha256(self.jar.read_bytes()).hexdigest()
        with patch.object(gate,'BCPKIX_SHA256',digest):
            self.issue.set('id','AllowAllHostnameVerifier')
            self.assertFalse(gate.reviewed_lint_issue(self.issue,True,True))
            self.issue.set('id','TrustAllX509TrustManager')
            ET.SubElement(self.issue,'location',file='application.kt')
            self.assertFalse(gate.reviewed_lint_issue(self.issue,True,True))

    def test_tampering_after_review_fails(self):
        digest=hashlib.sha256(self.jar.read_bytes()).hexdigest()
        with patch.object(gate,'BCPKIX_SHA256',digest):
            self.jar.write_bytes(b'changed binary')
            self.assertFalse(gate.reviewed_lint_issue(self.issue,True,True))

    def test_upstream_png_sources_match_manifest(self):
        self.assertTrue(gate.verify_png_sources())

    def test_missing_failed_empty_skipped_and_stale_runtime_reports_block(self):
        base=Path(self.temp.name)
        apk=base/'app.apk';apk.write_bytes(b'test apk')
        self.assertFalse(gate.runtime_tests_passed(base,apk))
        suite=ET.Element('testsuite',tests='4',failures='0',errors='0',skipped='0')
        for name in ('keystoreCiphertextSurvivesReopenAndRejectsTampering','rebuiltNativePngDecodesActualFileAndReleasesResources',
                     'nativeOcrReadsSyntheticStatementWithoutInternetPermission','pdfCropRendersOriginalAmountFromEncryptedAndRotatedPagesInMemory'):
            ET.SubElement(suite,'testcase',name=name)
        path=base/'TEST-runtime.xml'
        def write():ET.ElementTree(suite).write(path)
        write();self.assertTrue(gate.runtime_tests_passed(base,apk))
        for key in ('failures','errors','skipped'):
            suite.set(key,'1');write();self.assertFalse(gate.runtime_tests_passed(base,apk));suite.set(key,'0')
        suite.set('tests','0');write();self.assertFalse(gate.runtime_tests_passed(base,apk));suite.set('tests','4')
        ET.SubElement(suite,'system-err').text='Instrumentation: Process crashed'
        write();self.assertFalse(gate.runtime_tests_passed(base,apk));suite.remove(suite.find('system-err'))
        write();stamp=apk.stat().st_mtime-10;os.utime(path,(stamp,stamp))
        self.assertFalse(gate.runtime_tests_passed(base,apk))

if __name__=='__main__':unittest.main()
