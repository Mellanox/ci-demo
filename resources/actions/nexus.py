#!/usr/bin/env python3

__author__ = "Andrii Holovchenko"
__version__ = '1.0'

import requests
import json
import argparse
import sys
import logging
from urllib.parse import urljoin

logging.basicConfig(
    level=logging.INFO,
    format='[%(asctime)s] %(levelname)s '
           '[%(name)s.%(funcName)s:%(lineno)d] '
           '%(message)s',
    datefmt='%d/%b/%Y %H:%M:%S',
    stream=sys.stdout)


def usage():
    parser = argparse.ArgumentParser(description='Manage NEXUS 3 repositories')
    parser.add_argument('-n', '--name', type=str, required=True,
                        help='Set repository name')
    parser.add_argument(
                        '-u', '--url', type=str, required=True,
                        help='Nexus 3 repository URL.'
                             'Example: http://swx-repos.mtr.labs.mlnx'
                       )
    parser.add_argument('-U', '--user', type=str, required=True,
                        help='Nexus 3 API username')
    parser.add_argument('-P', '--password', type=str, required=True,
                        help='Nexus 3 API password')

    main_parser = argparse.ArgumentParser()

    subparsers = main_parser.add_subparsers(help='Nexus repository type',
                                            dest='repo_type')
    subparsers.required = True

    # YUM subparser
    p_yum = subparsers.add_parser('yum', parents=[parser],
                                  add_help=False)
    p_yum.add_argument('-d', '--repo-data-depth', type=int, default=1,
                       help='Repository data depth')
    p_yum.add_argument(
                       '--blob-store', dest='blob_store', type=str,
                       default='default',
                       help='Blob store used to store repository contents'
                      )
    p_yum.add_argument(
                       '--write-policy', dest='write_policy', type=str,
                       choices=['allow_once', 'allow', 'deny'],
                       default='allow_once',
                       help='controls if deployments of '
                            'and updates to assets are allowed'
                      )
    p_yum.add_argument(
                       '-a', '--action', choices=[
                                                  'delete',
                                                  'create',
                                                  'show',
                                                  'upload'
                                                 ],
                       default='show', help='Action to execute (default: show)'
                      )
    p_yum.add_argument('-f', '--file', type=str, help='Select file to upload')
    p_yum.add_argument(
                       '-p', '--upload_path', type=str,
                       help='Path to upload package (Example: /7/x86_64/)'
                      )

    # APT subparser
    p_apt = subparsers.add_parser('apt', parents=[parser],
                                  add_help=False)
    p_apt.add_argument(
                       '-a', '--action', choices=[
                                                  'delete',
                                                  'create',
                                                  'show',
                                                  'upload'
                                                 ],
                       default='show',
                       help='Action to execute (default: show)'
                      )
    p_apt.add_argument('--blob-store', dest='blob_store',
                       type=str, default='default',
                       help='Blob store used to store repository contents')
    p_apt.add_argument(
                       '--write-policy', dest='write_policy', type=str,
                       choices=['allow_once', 'allow', 'deny'],
                       default='allow_once',
                       help='controls if deployments of '
                            'and updates to assets are allowed'
                      )
    p_apt.add_argument(
                       '--distro', type=str,
                       help='UBUNTU/DEBIAN distribution '
                            '(Example: bionic, focal)'
                      )
    p_apt.add_argument('--passphrase', type=str,
                       help='Passphrase to access PGP signing key')
    p_apt.add_argument('-f', '--file', type=str, help='Select file to upload')

    p_apt_group = p_apt.add_mutually_exclusive_group()
    p_apt_group.add_argument(
                             '--keypair', type=str, default='default',
                             help='PGP signing key pair (armored private key '
                                  'e.g. gpg --export-secret-key --armor)'
                            )
    p_apt_group.add_argument(
                             '--keypair-file', type=argparse.FileType('r'),
                             dest='keypair_file',
                             help='Read PGP signing key pair from a file'
                            )

    args = main_parser.parse_args()

    if args.repo_type == 'apt':
        if args.action == 'create':
            if not args.keypair or not args.keypair_file:
                main_parser.error(
                                  'one of the arguments --keypair '
                                  '--keypair-file is required'
                                 )
            if not args.distro:
                main_parser.error(
                                  'the following arguments '
                                  'are required: --distro'
                                 )

    return main_parser, args


def delete_repository(url, name, user, password):

    api_url = urljoin(url, f'service/rest/v1/repositories/{name}')

    response = requests.delete(
                               api_url,
                               auth=requests.auth.HTTPBasicAuth(user, password)
                              )

    if response.status_code == 204:
        logging.info(f'Repository has been deleted: {name}')
        return 0
    elif response.status_code == 404:
        logging.error(f'Repository not found: {name}')
    elif response.status_code == 403:
        logging.error(f'Insufficient permissions to delete repository: {name}')
    else:
        logging.error('Unable to delete repository')
    logging.error(response.content)
    sys.exit(1)


def get_repositories(url):
    api_url = urljoin(url, 'service/rest/v1/repositories')
    response = requests.get(api_url)
    if response.status_code == 200:
        try:
            return json.loads(response.content)
        except json.decoder.JSONDecodeError:
            logging.error('Unable to decode json data')

    logging.error(f'ERROR: Please check if URL is valid: {api_url}')
    sys.exit(1)


def create_yum_repo(url, name, user, password, repo_data_depth,
                    blob_store, write_policy):

    api_url = urljoin(url, 'service/rest/v1/repositories/yum/hosted')

    params = {
          "name": name,
          "online": "true",
          "storage": {
                  "blobStoreName": blob_store,
                  "strictContentTypeValidation": "true",
                  "writePolicy": write_policy
                },
          "cleanup": {
                  "policyNames": [
                            "string"
                          ]
                },
          "component": {
                  "proprietaryComponents": "false"
                },
          "yum": {
                  "repodataDepth": repo_data_depth,
                  "deployPolicy": "STRICT"
                }
    }

    logging.info(f'Creating hosted yum repository: {name}')
    response = requests.post(api_url, json=params,
                             auth=requests.auth.HTTPBasicAuth(user, password))

    if response.status_code == 201:
        logging.info('Done')
    else:
        logging.error(f'Failed to create repository: {name}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)


def get_yum_repo(url, name, user=None, password=None):

    api_url = urljoin(url, f'service/rest/v1/repositories/yum/hosted/{name}')

    auth_params = None

    if user is not None and password is not None:
        auth_params = requests.auth.HTTPBasicAuth(user, password)

    response = requests.get(api_url, auth=auth_params)
    if response.status_code == 200:
        response_json = json.loads(response.content)
        print(json.dumps(response_json, indent=2))
    else:
        print(api_url)
        logging.error(f'Failed to get repository: {name}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)


def create_apt_repo(url, name, user, password,
                    blob_store, write_policy,
                    distribution, keypair=None, keypair_file=None,
                    passphrase=None):

    api_url = urljoin(url, 'service/rest/v1/repositories/apt/hosted')

    if keypair_file:
        keypair = keypair_file.read()

    params = {
          "name": name,
          "online": "true",
          "storage": {
            "blobStoreName": blob_store,
            "strictContentTypeValidation": "true",
            "writePolicy": write_policy
          },
          "cleanup": {
            "policyNames": [
              "string"
            ]
          },
          "component": {
            "proprietaryComponents": "true"
          },
          "apt": {
            "distribution": distribution
          },
          "aptSigning": {
            "keypair": keypair,
            "passphrase": passphrase
          }
    }

    logging.info(f'Creating hosted APT repository: {name}')
    response = requests.post(api_url, json=params,
                             auth=requests.auth.HTTPBasicAuth(user, password))

    if response.status_code == 201:
        logging.info('Done')
    else:
        logging.error(f'Failed to create repository: {name}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)


def upload_yum(url, name, file_path, user, password, upload_path=None):
    try:
        artifcat = open(file_path, 'rb').read()
    except IOError:
        logging.error(f'Unable to open file: {file_path}')
        sys.exit(1)

    fn = file_path.split('/')[-1]

    path = f'repository/{name}'

    if upload_path:
        path = f'{path}/{upload_path}'

    upload_url = urljoin(url, f'{path}/{fn}')

    response = requests.put(
                            upload_url, data=artifcat,
                            auth=requests.auth.HTTPBasicAuth(
                                                             args.user,
                                                             args.password
                                                            )
                           )
    if response.status_code != 200:
        logging.error(f'Unable to upload artifact {file_path} to {upload_url}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)


def upload_apt(url, name, file_path, user, password):
    try:
        artifcat = open(file_path, 'rb')
    except IOError:
        logging.error(f'Unable to open file: {file_path}')
        sys.exit(1)

    upload_url = urljoin(url, f'repository/{name}')

    # Forward slash at the end is mandatory for APT
    if not upload_url.endswith('/'):
        upload_url = upload_url + '/'

    response = requests.post(
                             upload_url, data=artifcat,
                             auth=requests.auth.HTTPBasicAuth(
                                                              args.user,
                                                              args.password
                                                             )
                            )
    if response.status_code != 201:
        logging.error(f'Unable to upload artifact {file_path} to {upload_url}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)
    logging.info('Done')


def get_apt_repo(url, name, user=None, password=None):
    api_url = urljoin(url, f'service/rest/v1/repositories/apt/hosted/{name}')

    auth_params = None

    if user is not None and password is not None:
        auth_params = requests.auth.HTTPBasicAuth(user, password)

    response = requests.get(api_url, auth=auth_params)
    if response.status_code == 200:
        response_json = json.loads(response.content)
        print(json.dumps(response_json, indent=2))
    else:
        logging.error(f'Failed to get repository: {name}')
        logging.error(f'Status code: {response.status_code}')
        logging.error(f'Response: {response.content}')
        sys.exit(1)


def main(parser, args):
    url = args.url
    repo_list = get_repositories(url)
    if args.action == 'delete':
        delete_repository(url, args.name, args.user, args.password)
    # YUM
    if args.repo_type == 'yum':
        if args.action == 'create':
            if args.name not in [repo['name'] for repo in repo_list]:
                create_yum_repo(url, args.name, args.user, args.password,
                                args.repo_data_depth, args.blob_store,
                                args.write_policy)
            else:
                logging.error(f'Repository already exists: {args.name}')
                sys.exit(1)
        if args.action == 'show':
            get_yum_repo(url, args.name, args.user, args.password)
        if args.action == 'upload':
            if not args.file or not args.upload_path:
                parser.error(
                             '--file --upload_path options are '
                             'required with --action=upload'
                            )
            if args.name not in [repo['name'] for repo in repo_list]:
                logging.error(f'Repository {args.name} doesn\'t exist')
                sys.exit(1)
            upload_yum(
                       url, args.name, args.file,
                       args.user, args.password,
                       upload_path=args.upload_path
                      )
    # APT
    if args.repo_type == 'apt':
        if args.action == 'create':
            if args.name not in [repo['name'] for repo in repo_list]:
                create_apt_repo(
                                url, args.name, args.user, args.password,
                                args.blob_store, args.write_policy,
                                args.distro, args.keypair,
                                args.keypair_file, args.passphrase
                               )
            else:
                logging.error(f'Repository already exists: {args.name}')
                sys.exit(1)
        if args.action == 'show':
            get_apt_repo(url, args.name, args.user, args.password)
        if args.action == 'upload':
            if not args.file:
                parser.error(
                             '--file option is required'
                             'with --action=upload'
                            )
            if args.name not in [repo['name'] for repo in repo_list]:
                logging.error(f'Repository {args.name} doesn\'t exist')
                sys.exit(1)
            upload_apt(url, args.name, args.file, args.user, args.password)


if __name__ == '__main__':
    parser, args = usage()
    main(parser, args)
