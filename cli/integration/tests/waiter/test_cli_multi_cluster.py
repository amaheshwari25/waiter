import logging
import os
from sys import version
import unittest
import uuid
import re

import pytest

from tests.waiter import cli, util


@pytest.mark.cli
@unittest.skipUnless(util.multi_cluster_tests_enabled(), 'Requires setting WAITER_TEST_MULTI_CLUSTER')
@pytest.mark.timeout(util.DEFAULT_TEST_TIMEOUT_SECS)
class MultiWaiterCliTest(util.WaiterTest):

    @classmethod
    def setUpClass(cls):
        cls.waiter_url_1 = util.retrieve_waiter_url()
        cls.waiter_url_2 = util.retrieve_waiter_url('WAITER_URL_2', 'http://localhost:9191')
        util.init_waiter_session(cls.waiter_url_1, cls.waiter_url_2)
        cli.write_base_config()

    def setUp(self):
        self.waiter_url_1 = type(self).waiter_url_1
        self.waiter_url_2 = type(self).waiter_url_2
        self.logger = logging.getLogger(__name__)
        self.waiter_1_cluster = util.retrieve_waiter_cluster_name(self.waiter_url_1)
        self.waiter_2_cluster = util.retrieve_waiter_cluster_name(self.waiter_url_2)

    def __two_cluster_config(self):
        return {'clusters': [{'name': 'waiter1', 'url': self.waiter_url_1},
                             {'name': 'waiter2', 'url': self.waiter_url_2}]}

    def test_federated_show(self):
        # Create in cluster #1
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            # Single query for the token name, federated across clusters
            config = self.__two_cluster_config()
            with cli.temp_config_file(config) as path:
                cp, tokens = cli.show_token('json', token_name=token_name, flags='--config %s' % path)
                versions = [t['version'] for t in tokens]
                self.assertEqual(0, cp.returncode, cp.stderr)
                self.assertEqual(1, len(tokens), tokens)
                self.assertIn(version_1, versions)

                # Create in cluster #2
                version_2 = str(uuid.uuid4())
                util.post_token(self.waiter_url_2, token_name, {'version': version_2})
                try:
                    # Again, single query for the token name, federated across clusters
                    cp, tokens = cli.show_token('json', token_name=token_name, flags='--config %s' % path)
                    versions = [t['version'] for t in tokens]
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertEqual(2, len(tokens), tokens)
                    self.assertIn(version_1, versions)
                    self.assertIn(version_2, versions)
                finally:
                    util.delete_token(self.waiter_url_2, token_name)
        finally:
            util.delete_token(self.waiter_url_1, token_name)

    def __test_show_single_cluster_group(self, no_services=False, enforce_cluster=False):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'production'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'production'}]}
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        token_1 = util.minimal_service_description(**{'cluster': 'waiter1', 'version': version_1})
        util.post_token(self.waiter_url_1, token_name, token_1, update_mode_admin=True)
        try:
            service_id_1 = util.ping_token(self.waiter_url_1, token_name)
            version_2 = str(uuid.uuid4())
            token_2 = util.minimal_service_description(**{'cluster': 'waiter1', 'version': version_2})
            util.post_token(self.waiter_url_2, token_name, token_2, update_mode_admin=True)
            try:
                service_id_2 = util.ping_token(self.waiter_url_2, token_name)
                with cli.temp_config_file(config) as path:
                    show_flags = '--no-services' if no_services else ''
                    cli_flags = f'--config {path}' + (' --cluster waiter1' if enforce_cluster else '')
                    cp = cli.show(token_name=token_name, flags=cli_flags, show_flags=show_flags)
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    if enforce_cluster:
                        self.assertIn(f'waiter1 / {token_name}', cli.stdout(cp))                    
                    else:
                        self.assertIn(f'production / {token_name}', cli.stdout(cp))
                    self.assertIn(version_1, cli.stdout(cp))
                    self.assertEqual(1, cli.stdout(cp).count(token_name))
                    if no_services:
                        self.assertNotIn(service_id_1, cli.stdout(cp))
                        self.assertNotIn(service_id_2, cli.stdout(cp))
                    else:
                        if enforce_cluster:
                            expected_service_count = 1
                            expected_inst_count = 1
                            expected_total_mem = token_1['mem']
                            expected_total_cpus = token_1['cpus']
                        else:
                            expected_service_count = 2
                            expected_inst_count = 2
                            expected_total_mem = token_1["mem"] + token_2["mem"]
                            expected_total_cpus = token_1["cpus"] + token_2["cpus"]
                            self.assertEqual(1, cli.stdout(cp).count(service_id_2))
                            self.assertIsNotNone(re.search(f'^{service_id_2}\\s+waiter2[^\\n]+Running[^\\n]+Not Current$', cli.stdout(cp), re.MULTILINE))
                        self.assertIsNotNone(re.search(f'^# Services\\s+{expected_service_count}$', cli.stdout(cp), re.MULTILINE))
                        self.assertIsNotNone(re.search(f'^# Failing\\s+0$', cli.stdout(cp), re.MULTILINE))
                        self.assertIsNotNone(re.search(f'^# Instances\\s+{expected_inst_count}$', cli.stdout(cp), re.MULTILINE))
                        self.assertIsNotNone(re.search(f'^Total Memory\\s+{expected_total_mem} MiB$', cli.stdout(cp), re.MULTILINE))
                        self.assertIsNotNone(re.search(f'^Total CPUs\\s+{expected_total_cpus}$', cli.stdout(cp), re.MULTILINE))
                        self.assertEqual(1, cli.stdout(cp).count(service_id_1))
                        self.assertIsNotNone(re.search(f'^{service_id_1}\\s+waiter1[^\\n]+Running[^\\n]+Current$', cli.stdout(cp), re.MULTILINE))
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=True, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=True, kill_services=True)

    def test_show_single_cluster_group(self):
        self.__test_show_single_cluster_group()

    def test_show_single_cluster_group_no_services(self):
        self.__test_show_single_cluster_group(no_services=True)
    
    def test_show_single_cluster_group_enforce_cluster(self):
        self.__test_show_single_cluster_group(enforce_cluster=True)

    def __test_show_multiple_cluster_groups(self, no_services=False, enforce_cluster=False):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'production'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'staging'}]}
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        token_1 = util.minimal_service_description(**{'cluster': 'waiter1', 'version': version_1})
        util.post_token(self.waiter_url_1, token_name, token_1, update_mode_admin=True)
        try:
            service_id_1 = util.ping_token(self.waiter_url_1, token_name)
            version_2 = str(uuid.uuid4())
            token_2 = util.minimal_service_description(**{'cluster': 'waiter2', 'version': version_2})
            util.post_token(self.waiter_url_2, token_name, token_2, update_mode_admin=True)
            try:
                service_id_2 = util.ping_token(self.waiter_url_2, token_name)
                with cli.temp_config_file(config) as path:
                    show_flags = '--no-services' if no_services else ''
                    cli_flags = f'--config {path}' + (' --cluster waiter1' if enforce_cluster else '')
                    cp = cli.show(token_name=token_name, flags=cli_flags, show_flags=show_flags)
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertEqual(1, len(re.findall(f'^Version\\s+{version_1}$', cli.stdout(cp), re.MULTILINE)))
                    if enforce_cluster:
                        expected_token_count = 1
                        self.assertIn(f'waiter1 / {token_name}', cli.stdout(cp))
                        self.assertEqual(1, cli.stdout(cp).count(token_name))
                    else:
                        expected_token_count = 2
                        self.assertIn(f'production / {token_name}', cli.stdout(cp))
                        self.assertIn(f'staging / {token_name}', cli.stdout(cp))
                        self.assertEqual(1, len(re.findall(f'^Version\\s+{version_2}$', cli.stdout(cp), re.MULTILINE)))
                        self.assertEqual(2, cli.stdout(cp).count(token_name))
                    if no_services:
                        self.assertNotIn(service_id_1, cli.stdout(cp))
                        self.assertNotIn(service_id_2, cli.stdout(cp))
                    else:
                        self.assertEqual(1, cli.stdout(cp).count(service_id_1))
                        self.assertEqual(expected_token_count, len(re.findall('^# Services\\s+1$', cli.stdout(cp), re.MULTILINE)))
                        self.assertEqual(expected_token_count, len(re.findall('^# Failing\\s+0$', cli.stdout(cp), re.MULTILINE)))
                        self.assertEqual(expected_token_count, len(re.findall('^# Instances\\s+1$', cli.stdout(cp), re.MULTILINE)))
                        self.assertEqual(expected_token_count, len(re.findall(f'^Total Memory\\s+{token_1["mem"]} MiB$', cli.stdout(cp), re.MULTILINE)))
                        self.assertEqual(expected_token_count, len(re.findall(f'^Total CPUs\\s+{token_1["cpus"]}$', cli.stdout(cp), re.MULTILINE)))
                        self.assertIsNotNone(re.search(f'^{service_id_1}\\s+waiter1[^\\n]+Running[^\\n]+Current$', cli.stdout(cp), re.MULTILINE))
                        if not enforce_cluster:
                            self.assertEqual(1, cli.stdout(cp).count(service_id_2))
                            self.assertEqual(1, cli.stdout(cp).count(service_id_2))
                            self.assertIsNotNone(re.search(f'^{service_id_2}\\s+waiter2[^\\n]+Running[^\\n]+Current$', cli.stdout(cp), re.MULTILINE))
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=True, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=True, kill_services=True)

    def test_show_multiple_cluster_groups(self):
        self.__test_show_multiple_cluster_groups()

    def test_show_multiple_cluster_groups_no_services(self):
        self.__test_show_multiple_cluster_groups(no_services=True)

    def test_show_multiple_cluster_groups_enforce_cluster(self):
        self.__test_show_multiple_cluster_groups(enforce_cluster=True)

    def test_federated_delete(self):
        # Create in cluster #1
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            # Create in cluster #2
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'version': version_2})
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # Delete the token in both clusters
                    cp = cli.delete(token_name=token_name, flags='--config %s' % path, delete_flags='--force')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('exists in 2 cluster(s)', cli.stdout(cp))
                    self.assertIn('waiter1', cli.stdout(cp))
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(2, cli.stdout(cp).count('Deleting token'))
                    self.assertEqual(2, cli.stdout(cp).count('Successfully deleted'))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=404)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=404)
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=False)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=False)

    def test_delete_single_cluster(self):
        # Create in cluster #1
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            # Create in cluster #2
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'version': version_2})
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # Delete the token in one cluster only
                    cp = cli.delete(token_name=token_name, flags=f'--config {path} --cluster waiter2')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertNotIn('exists in 2 clusters', cli.stdout(cp))
                    self.assertNotIn('waiter1', cli.stdout(cp))
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(1, cli.stdout(cp).count('Deleting token'))
                    self.assertEqual(1, cli.stdout(cp).count('Successfully deleted'))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=200)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=404)
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=False)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=True)

    def test_delete_token_in_multiple_cluster_groups(self):
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'version': version_2})
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # failed delete because the target cluster couldn't be inferred when the token is in mutliple cluster groups
                    cp = cli.delete(token_name=token_name, flags=f'--config {path}')
                    self.assertEqual(1, cp.returncode, cp.stderr)
                    self.assertIn('Could not infer the target cluster for this operation', cli.stderr(cp))
                    self.assertIn('waiter1', cli.stderr(cp))
                    self.assertIn('waiter2', cli.stderr(cp))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=200)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=False)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=True)

    def test_delete_token_in_single_cluster_group(self):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'production'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'production'}]}
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'cluster': 'waiter1', 'version': version_1}, update_mode_admin=True)
        try:
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'cluster': 'waiter1', 'version': version_2}, update_mode_admin=True)
            try:
                with cli.temp_config_file(config) as path:
                    # deletes token in the primary cluster only, and relies on token syncer that will sync the delete
                    cp = cli.delete(token_name=token_name, flags=f'--config {path}')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn(f'Successfully deleted {token_name} in waiter1.', cli.stdout(cp))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=404)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
                    # attempting to delete again yields a no-op as the token is effectively deleted in the cluster group
                    cp_noop = cli.delete(token_name=token_name, flags=f'--config {path}')
                    self.assertEqual(1, cp_noop.returncode, cp_noop.stderr)
                    self.assertIn('No matching data found in', cli.stdout(cp_noop))
                    self.assertIn('waiter1', cli.stdout(cp_noop))
                    self.assertIn('waiter2', cli.stdout(cp_noop))
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=False)
        
    def test_delete_token_in_single_cluster_group_force(self):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'production'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'production'}]}
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'version': version_2})
            try:
                with cli.temp_config_file(config) as path:
                    # deletes token in the primary cluster with force will delete the token in all clusters, not just primary cluster
                    cp = cli.delete(token_name=token_name, flags=f'--config {path}', delete_flags='-f')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn(f'Successfully deleted {token_name} in waiter1.', cli.stdout(cp))
                    self.assertIn(f'Successfully deleted {token_name} in waiter2.', cli.stdout(cp))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=404)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=404)
                    # attempting to delete again yields a no-op as the token should be completely deleted
                    cp_noop = cli.delete(token_name=token_name, flags=f'--config {path}')
                    self.assertEqual(1, cp_noop.returncode, cp_noop.stderr)
                    self.assertIn('No matching data found in', cli.stdout(cp_noop))
                    self.assertIn('waiter1', cli.stdout(cp_noop))
                    self.assertIn('waiter2', cli.stdout(cp_noop))
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=False)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=False)

    def test_delete_token_sync_disabled(self):
        token_name = self.token_name()
        version_1 = str(uuid.uuid4())
        util.post_token(self.waiter_url_1, token_name, {'version': version_1})
        try:
            version_2 = str(uuid.uuid4())
            util.post_token(self.waiter_url_2, token_name, {'version': version_2,
                                                            'metadata': {'waiter-token-sync-opt-out': 'true'}})
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # Delete the token in both clusters
                    cp = cli.delete(token_name=token_name, flags=f'--config {path}', stdin='Yes\nYes\n'.encode('utf8'))
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn(f'Successfully deleted {token_name} in waiter1.', cli.stdout(cp))
                    self.assertIn(f'Successfully deleted {token_name} in waiter2.', cli.stdout(cp))
                    util.load_token(self.waiter_url_1, token_name, expected_status_code=404)
                    util.load_token(self.waiter_url_2, token_name, expected_status_code=404)
            finally:
                util.delete_token(self.waiter_url_2, token_name, assert_response=False)
        finally:
            util.delete_token(self.waiter_url_1, token_name, assert_response=False)

    def test_federated_ping(self):
        # Create in cluster #1
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description())
        try:
            # Create in cluster #2
            util.post_token(self.waiter_url_2, token_name, util.minimal_service_description())
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # Ping the token in both clusters
                    cp = cli.ping(token_name_or_service_id=token_name, flags=f'--config {path}')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('waiter1', cli.stdout(cp))
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(2, cli.stdout(cp).count('Pinging token'))
                    self.assertEqual(1, len(util.services_for_token(self.waiter_url_1, token_name)))
                    self.assertEqual(1, len(util.services_for_token(self.waiter_url_2, token_name)))
            finally:
                util.delete_token(self.waiter_url_2, token_name, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, kill_services=True)

    def test_federated_kill(self):
        # Create in cluster #1
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description())
        try:
            # Create in cluster #2
            util.post_token(self.waiter_url_2, token_name, util.minimal_service_description())
            try:
                # Ping the token in both clusters
                util.ping_token(self.waiter_url_1, token_name)
                util.ping_token(self.waiter_url_2, token_name)

                # Kill the services in both clusters
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    cp = cli.kill(token_name_or_service_id=token_name, flags=f'--config {path}', kill_flags='--force')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('waiter1', cli.stdout(cp))
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(2, cli.stdout(cp).count('Killing service'))
                    self.assertEqual(2, cli.stdout(cp).count('Successfully killed'))
                    self.assertEqual(0, len(util.services_for_token(self.waiter_url_1, token_name)))
                    self.assertEqual(0, len(util.services_for_token(self.waiter_url_2, token_name)))
            finally:
                util.delete_token(self.waiter_url_2, token_name, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, kill_services=True)

    def test_federated_kill_service_id(self):
        # Create in cluster #1
        token_name = self.token_name()
        service_description = util.minimal_service_description()
        util.post_token(self.waiter_url_1, token_name, service_description)
        try:
            # Create in cluster #2
            util.post_token(self.waiter_url_2, token_name, service_description)
            try:
                # Ping the token in both clusters
                service_id_1 = util.ping_token(self.waiter_url_1, token_name)
                service_id_2 = util.ping_token(self.waiter_url_2, token_name)
                self.assertEqual(service_id_1, service_id_2)

                # Kill the services in both clusters
                util.kill_services_using_token(self.waiter_url_1, token_name)
                util.kill_services_using_token(self.waiter_url_2, token_name)

                # Attempt to kill using the CLI
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # First with --force
                    cp = cli.kill(token_name_or_service_id=service_id_1, flags=f'--config {path}',
                                  kill_flags='--force --service-id')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('waiter1', cli.stdout(cp))
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(2, cli.stdout(cp).count('cannot be killed because it is already Inactive'))

                    # Then, without --force
                    cp = cli.kill(token_name_or_service_id=service_id_1, flags=f'--config {path}',
                                  kill_flags='--service-id')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn(f'waiter1 / {service_id_1}', cli.stdout(cp))
                    self.assertIn(f'waiter2 / {service_id_2}', cli.stdout(cp))
                    self.assertIn(f'{self.waiter_url_1}/apps/{service_id_1}', cli.stdout(cp))
                    self.assertIn(f'{self.waiter_url_2}/apps/{service_id_2}', cli.stdout(cp))
                    self.assertEqual(2, cli.stdout(cp).count('cannot be killed because it is already Inactive'))
                    self.assertEqual(2, cli.stdout(cp).count('Run as user'))
            finally:
                util.delete_token(self.waiter_url_2, token_name, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, kill_services=True)

    def test_update_non_default_cluster(self):
        # Set up the config so that cluster #1 is the default
        config = {'clusters': [{'name': 'waiter1', 'url': self.waiter_url_1, 'default-for-create': True},
                               {'name': 'waiter2', 'url': self.waiter_url_2}]}

        # Create in cluster #2 (the non-default)
        token_name = self.token_name()
        service_description = util.minimal_service_description()
        util.post_token(self.waiter_url_2, token_name, service_description)
        try:
            # Update using the CLI, which should update in cluster #2
            with cli.temp_config_file(config) as path:
                version = str(uuid.uuid4())
                cp = cli.update(token_name=token_name, flags=f'--config {path}', update_flags=f'--version {version}')
                self.assertEqual(0, cp.returncode, cp.stderr)
                self.assertNotIn('waiter1', cli.stdout(cp))
                self.assertIn('waiter2', cli.stdout(cp))
                token_1 = util.load_token(self.waiter_url_1, token_name, expected_status_code=404)
                token_2 = util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
                self.assertNotIn('version', token_1)
                self.assertEqual(version, token_2['version'])
        finally:
            util.delete_token(self.waiter_url_2, token_name)

    def _test_choose_latest_configured_cluster(self, cluster_test_configs, expected_updated_cluster_index):
        config = {'clusters': cluster_test_configs}
        token_name = self.token_name()
        expected_cluster_with_latest_token = cluster_test_configs[expected_updated_cluster_index]
        # last token defined in the cluster_test_configs array will be the latest token
        for cluster_test_config in cluster_test_configs:
            util.post_token(cluster_test_config['url'], token_name, cluster_test_config['token-to-create'],
                            update_mode_admin=True)
        try:
            with cli.temp_config_file(config) as path:
                version = str(uuid.uuid4())
                cp = cli.update(token_name=token_name, flags=f'--config {path}', update_flags=f'--version {version}')
                self.assertEqual(0, cp.returncode, cp.stderr)
                self.assertIn(expected_cluster_with_latest_token['name'], cli.stdout(cp))
                modified_token = util.load_token(expected_cluster_with_latest_token['url'], token_name,
                                                 expected_status_code=200)
                self.assertEqual(version, modified_token['version'])
                for cluster_test_config in cluster_test_configs:
                    waiter_url = cluster_test_config['url']
                    if waiter_url != expected_cluster_with_latest_token['url']:
                        not_modified_token = util.load_token(waiter_url, token_name, expected_status_code=200)
                        self.assertNotEqual(version, not_modified_token)
                        self.assertNotIn(cluster_test_config['name'], cli.stdout(cp))
        finally:
            for cluster_test_config in cluster_test_configs:
                util.delete_token(cluster_test_config['url'], token_name)

    def test_update_token_latest_configured_same_cluster(self):
        sync_group_name = 'group_name'
        cluster_test_configs = [{'name': 'waiter1',
                                 'url': self.waiter_url_1,
                                 'token-to-create': util.minimal_service_description(cluster=self.waiter_1_cluster),
                                 'default-for-create': True,
                                 'sync-group': sync_group_name},
                                {'name': 'waiter2',
                                 'url': self.waiter_url_2,
                                 'token-to-create': util.minimal_service_description(cluster=self.waiter_2_cluster),
                                 'sync-group': sync_group_name}]
        self._test_choose_latest_configured_cluster(cluster_test_configs, 1)

    def test_update_token_latest_configured_different_cluster(self):
        sync_group_name = 'group_name'
        cluster_test_configs = [{'name': 'waiter1',
                                 'url': self.waiter_url_1,
                                 'token-to-create': util.minimal_service_description(cluster=self.waiter_1_cluster),
                                 'default-for-create': True,
                                 'sync-group': sync_group_name},
                                {'name': 'waiter2',
                                 'url': self.waiter_url_2,
                                 'token-to-create': util.minimal_service_description(cluster=self.waiter_1_cluster),
                                 'sync-group': sync_group_name}]
        self._test_choose_latest_configured_cluster(cluster_test_configs, 0)

    def test_update_token_latest_configured_to_missing_cluster(self):
        sync_group_1 = "sync-group-1"
        unlisted_cluster_name = "unlisted_cluster"
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': sync_group_1},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': sync_group_1}]}
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description(cluster=self.waiter_1_cluster))
        util.post_token(self.waiter_url_2, token_name, util.minimal_service_description(cluster=unlisted_cluster_name))
        try:
            with cli.temp_config_file(config) as path:
                version = str(uuid.uuid4())
                cp = cli.update(token_name=token_name, flags=f'--config {path}', update_flags=f'--version {version}')
                self.assertEqual(1, cp.returncode, cp.stderr)
                self.assertIn('The token is configured in cluster', cli.stderr(cp))
                self.assertIn(unlisted_cluster_name, cli.stderr(cp))
                self.assertIn(self.waiter_1_cluster, cli.stderr(cp))
                self.assertIn(self.waiter_2_cluster, cli.stderr(cp))
        finally:
            util.delete_token(self.waiter_url_1, token_name)
            util.delete_token(self.waiter_url_2, token_name)

    def _test_update_token_multiple_sync_groups(self, config):
        token_name = self.token_name()
        util.post_token(self.waiter_url_2, token_name, util.minimal_service_description(cluster=self.waiter_2_cluster))
        try:
            with cli.temp_config_file(config) as path:
                version = str(uuid.uuid4())
                cp = cli.update(token_name=token_name, flags=f'--config {path}', update_flags=f'--version {version}')
                self.assertEqual(0, cp.returncode, cp.stderr)
                self.assertIn('waiter2', cli.stdout(cp))
                token_2 = util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
                self.assertEqual(version, token_2['version'])
        finally:
            util.delete_token(self.waiter_url_2, token_name)

    def test_update_token_multiple_sync_groups_no_conflict(self):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': "sync-group-1"},
                               {'name': 'waiter2', 'url': self.waiter_url_2,
                                'sync-group': "sync-group-2"}]}
        self._test_update_token_multiple_sync_groups(config)

    def test_update_token_multiple_sync_groups_not_listed(self):
        # by default, if no sync group is listed the sync-group is given a unique group
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True},
                               {'name': 'waiter2', 'url': self.waiter_url_2}]}
        self._test_update_token_multiple_sync_groups(config)

    def test_update_token_multiple_sync_groups_with_conflict(self):
        sync_group_1 = "sync-group-1"
        sync_group_2 = "sync-group-2"
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': sync_group_1},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': sync_group_2}]}
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description(cluster=self.waiter_1_cluster))
        util.post_token(self.waiter_url_2, token_name, util.minimal_service_description(cluster=self.waiter_2_cluster))
        try:
            with cli.temp_config_file(config) as path:
                version = str(uuid.uuid4())
                cp = cli.update(token_name=token_name, flags=f'--config {path}', update_flags=f'--version {version}')
                self.assertEqual(1, cp.returncode, cp.stderr)
                self.assertIn('Could not infer the target cluster', cli.stderr(cp))
                self.assertIn(sync_group_1, cli.stderr(cp))
                self.assertIn(sync_group_2, cli.stderr(cp))
        finally:
            util.delete_token(self.waiter_url_1, token_name)
            util.delete_token(self.waiter_url_2, token_name)

    def test_maintenance_start_latest_configured_cluster(self):
        custom_maintenance_message = "custom maintenance message"
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'group_name'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'group_name'}]}
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description(cluster=self.waiter_1_cluster))
        util.post_token(self.waiter_url_2, token_name, util.minimal_service_description(cluster=self.waiter_2_cluster))
        try:
            with cli.temp_config_file(config) as path:
                cp = cli.maintenance('start', token_name, flags=f'--config {path}',
                                     maintenance_flags=f'"{custom_maintenance_message}"')
                self.assertEqual(0, cp.returncode, cp.stderr)
                token_1 = util.load_token(self.waiter_url_1, token_name, expected_status_code=200)
                token_2 = util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
                self.assertEqual(custom_maintenance_message, token_2['maintenance']['message'])
                self.assertTrue('maintenance' not in token_1)
        finally:
            util.delete_token(self.waiter_url_1, token_name)
            util.delete_token(self.waiter_url_2, token_name)

    def test_maintenance_stop_latest_configured_cluster(self):
        custom_maintenance_message = "custom maintenance message"
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'group_name'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'group_name'}]}
        token_name = self.token_name()
        util.post_token(self.waiter_url_1,
                        token_name,
                        util.minimal_service_description(cluster=self.waiter_1_cluster,
                                                         maintenance={'message': custom_maintenance_message}))
        util.post_token(self.waiter_url_2,
                        token_name,
                        util.minimal_service_description(cluster=self.waiter_2_cluster,
                                                         maintenance={'message': custom_maintenance_message}))
        try:
            with cli.temp_config_file(config) as path:
                cp = cli.maintenance('stop', token_name, flags=f'--config {path}')
                self.assertEqual(0, cp.returncode, cp.stderr)
                token_1 = util.load_token(self.waiter_url_1, token_name, expected_status_code=200)
                token_2 = util.load_token(self.waiter_url_2, token_name, expected_status_code=200)
                self.assertTrue('maintenance' not in token_2)
                self.assertTrue(custom_maintenance_message, token_1['maintenance']['message'])
        finally:
            util.delete_token(self.waiter_url_1, token_name)
            util.delete_token(self.waiter_url_2, token_name)

    def test_maintenance_check_latest_configured_cluster(self):
        config = {'clusters': [{'name': 'waiter1',
                                'url': self.waiter_url_1,
                                'default-for-create': True,
                                'sync-group': 'group_name'},
                               {'name': 'waiter2',
                                'url': self.waiter_url_2,
                                'sync-group': 'group_name'}]}
        token_name = self.token_name()
        util.post_token(self.waiter_url_1,
                        token_name,
                        util.minimal_service_description(cluster=self.waiter_1_cluster,
                                                         maintenance={'message': "custom maintenance message"}))
        util.post_token(self.waiter_url_2,
                        token_name,
                        util.minimal_service_description(cluster=self.waiter_2_cluster))
        try:
            with cli.temp_config_file(config) as path:
                cp = cli.maintenance('check', token_name, flags=f'--config {path}')
                self.assertEqual(1, cp.returncode, cp.stderr)
                self.assertIn(f'{token_name} is not in maintenance mode', cli.stdout(cp))
        finally:
            util.delete_token(self.waiter_url_1, token_name)
            util.delete_token(self.waiter_url_2, token_name)

    def test_ping_via_token_cluster(self):
        # Create in cluster #1
        token_name = self.token_name()
        token_data = util.minimal_service_description()
        util.post_token(self.waiter_url_1, token_name, token_data)
        try:
            # Create in cluster #2
            token_data['cluster'] = util.load_token(self.waiter_url_1, token_name)['cluster']
            util.post_token(self.waiter_url_2, token_name, token_data)
            try:
                config = self.__two_cluster_config()
                with cli.temp_config_file(config) as path:
                    # Ping the token, which should only ping in cluster #1
                    cp = cli.ping(token_name_or_service_id=token_name, flags=f'--config {path}')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('waiter1', cli.stdout(cp))
                    self.assertEqual(1, cli.stdout(cp).count('Pinging token'))
                    self.assertEqual(1, cli.stdout(cp).count('Not pinging token'))
                    self.assertEqual(1, len(util.services_for_token(self.waiter_url_1, token_name)))
                    self.assertEqual(0, len(util.services_for_token(self.waiter_url_2, token_name)))

                    # Ping the token in cluster #2 explicitly
                    cp = cli.ping(token_name_or_service_id=token_name, flags=f'--config {path} --cluster waiter2')
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn('waiter2', cli.stdout(cp))
                    self.assertEqual(1, cli.stdout(cp).count('Pinging token'))
                    self.assertEqual(0, cli.stdout(cp).count('Not pinging token'))
                    self.assertEqual(1, len(util.services_for_token(self.waiter_url_2, token_name)))
            finally:
                util.delete_token(self.waiter_url_2, token_name, kill_services=True)
        finally:
            util.delete_token(self.waiter_url_1, token_name, kill_services=True)

    def test_federated_tokens(self):
        # Create in cluster #1
        token_name = self.token_name()
        util.post_token(self.waiter_url_1, token_name, util.minimal_service_description())
        try:
            # Single query for the tokens, federated across clusters
            cluster_1 = f'foo_{uuid.uuid4()}'
            cluster_2 = f'bar_{uuid.uuid4()}'
            config = {'clusters': [{'name': cluster_1, 'url': self.waiter_url_1},
                                   {'name': cluster_2, 'url': self.waiter_url_2}]}
            with cli.temp_config_file(config) as path:
                cp, tokens = cli.tokens_data(flags='--config %s' % path)
                tokens = [t for t in tokens if t['token'] == token_name]
                self.assertEqual(0, cp.returncode, cp.stderr)
                self.assertEqual(1, len(tokens), tokens)

                # Create in cluster #2
                util.post_token(self.waiter_url_2, token_name, util.minimal_service_description())
                try:
                    # Again, single query for the tokens, federated across clusters
                    cp, tokens = cli.tokens_data(flags='--config %s' % path)
                    tokens = [t for t in tokens if t['token'] == token_name]
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertEqual(2, len(tokens), tokens)

                    # Test the secondary sort on cluster
                    cp = cli.tokens(flags='--config %s' % path)
                    stdout = cli.stdout(cp)
                    self.assertEqual(0, cp.returncode, cp.stderr)
                    self.assertIn(cluster_1, stdout)
                    self.assertIn(cluster_2, stdout)
                    self.assertLess(stdout.index(cluster_2), stdout.index(cluster_1))
                finally:
                    util.delete_token(self.waiter_url_2, token_name)
        finally:
            util.delete_token(self.waiter_url_1, token_name)

    def __test_ssh_same_service_id_on_multiple_clusters(self, create_empty_service=True):
        token_name = self.token_name()
        service_desc = util.minimal_service_description()
        util.post_token(self.waiter_url_1, token_name, service_desc)
        util.post_token(self.waiter_url_2, token_name, service_desc)
        try:
            # service ids should be the same as their service descriptions are the same
            service1_id = util.create_empty_service_with_token(self.waiter_url_1, token_name) if create_empty_service \
                else util.ping_token(self.waiter_url_1, token_name)
            service2_id = util.ping_token(self.waiter_url_2, token_name)
            self.assertEqual(service1_id, service2_id)
            instances1 = [] if create_empty_service else \
                util.instances_for_service(self.waiter_url_1, service1_id)['active-instances']
            instances2 = util.instances_for_service(self.waiter_url_2, service2_id)['active-instances']
            possible_instances = instances1 + instances2
            env = os.environ.copy()
            env['WAITER_SSH'] = 'echo'
            env['WAITER_KUBECTL'] = 'echo'
            config = self.__two_cluster_config()
            with cli.temp_config_file(config) as path:
                cp = cli.ssh(token_or_service_id_or_instance_id=service1_id, stdin='1\n'.encode('utf8'), ssh_flags='-s',
                             flags=f'--config {path}', env=env)
                stdout = cli.stdout(cp)
                self.assertEqual(0, cp.returncode, cp.stderr)
                # all instances should have been an option when prompting
                self.assertFalse(util.get_instances_not_in_output(possible_instances, stdout))
                # any one of the instances should have been attempted to ssh into
                ssh_instance1 = None if create_empty_service else \
                    util.get_ssh_instance_from_output(self.waiter_url_1, instances1, stdout)
                ssh_instance2 = util.get_ssh_instance_from_output(self.waiter_url_2, instances2, stdout)
                found = ssh_instance1 is not None or ssh_instance2 is not None
                self.assertTrue(found, msg=f"None of the possible instances {possible_instances} were detected in ssh "
                                           f"command output: \n{stdout}")
        finally:
            util.delete_token(self.waiter_url_1, token_name, kill_services=True)
            util.delete_token(self.waiter_url_2, token_name, kill_services=True)

    def test_ssh_service_id_on_multiple_clusters_no_instances(self):
        self.__test_ssh_same_service_id_on_multiple_clusters(create_empty_service=False)

    def test_ssh_service_id_on_multiple_clusters_multiple_instances(self):
        self.__test_ssh_same_service_id_on_multiple_clusters()
