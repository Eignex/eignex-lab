import importlib.util
from pathlib import Path
import unittest


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec); spec.loader.exec_module(value)
    return value


render = module('host_bootstrap', Path(__file__).resolve().parents[1] / 'common/bootstrap.py').render


class BootstrapTest(unittest.TestCase):
    def setUp(self):
        self.config = dict(architecture='x86_64', minimumFeatures=['avx2'], vcpus=16, memoryMiB=65536,
            servers=['100.64.0.10:4647'], pool='general', nodeClass='general', datacenter='lab', workerImage='registry/worker@sha256:' + 'a'*64)
        self.secret = dict(ca='-----BEGIN CERTIFICATE-----', certificate='-----BEGIN CERTIFICATE-----', privateKey='-----BEGIN PRIVATE KEY-----')
        self.probe = dict(detected='1', arch='x86_64', features='avx avx2', model='CPU')

    def test_draining_tls_and_cloud_identity_are_host_only(self):
        value = render(self.config, self.secret, 'i-instance', self.probe, 16, 64000)
        self.assertTrue(value['leave_on_terminate'])
        self.assertTrue(value['tls']['verify_https_client'])
        self.assertEqual('60s', value['client']['drain_on_shutdown']['deadline'])
        self.assertEqual('i-instance', value['client']['meta']['lab_capacity_id'])
        self.assertTrue(value['client']['host_volume']['lab-status']['read_only'])
        self.assertFalse(value['plugin'][0]['raw_exec']['config'][0]['enabled'])
        self.assertNotIn('token', str(value))

    def test_incompatible_hardware_and_mutable_images_never_enable_scheduling(self):
        for config, probe, cpus, memory in [(self.config, self.probe | {'detected':'0'},16,64000),
            (self.config, self.probe | {'features':'avx'},16,64000), (self.config,self.probe,8,64000),
            (self.config,self.probe,16,32000), (self.config | {'workerImage':'worker:latest'},self.probe,16,64000)]:
            with self.assertRaises(ValueError):
                render(config, self.secret, 'i-instance', probe, cpus, memory)
