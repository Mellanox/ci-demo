#!/usr/bin/env python3
import os
import argparse
import yaml
from string import Template

template = """apiVersion: v1
kind: Pod
metadata:
  name: "{podname}-debug-reproducer"
spec:
  containers:
  - command:
    - cat
    image: {imageurl}
    imagePullPolicy: "Always"
    name: "{imagename}"
    resources:
      limits: {limits}
      requests: {requests}
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    {volumeMounts}
  nodeSelector:
    {nodeSelector}
  restartPolicy: "Never"
  securityContext:
    runAsGroup: {uid}
    runAsUser: {gid}
  hostNetwork: true
  volumes:
  {volumes}
"""

volumes_tmpl = """
  - hostPath:
      path: "{hostPath}"
    name: "volume-{volumeid}"
"""

mounts_tmpl = """
    - mountPath: "{mountPath}"
      name: "volume-{volumeid}"
      readOnly: false
"""

nodeSelector_tmpl = """
    beta.kubernetes.io/os: "{os}"
    beta.kubernetes.io/arch: "{arch}"
"""

arch_to_k8s_arch = {
    "x86_64": "amd64",
    "aarch64": "arm64",
    "ppc64le": "ppc64le",
}


def usage():
    parser = argparse.ArgumentParser(description='Generate pod.yaml from ci-demo project file')
    parser.add_argument('-file', metavar='filename', type=str, help='Path to job_matrix.yaml file', required=True)
    parser.add_argument('-out',  metavar='filename', type=str, help='Output file for pod.yaml file, default: stdout', required=False)
    parser.add_argument('-image_name', metavar='string', type=str, help="Select container name: default: find 1st from 'runs_on_dockers' list", required=False)
    parser.add_argument('-arch', metavar='string', type=str, help='Select container arch, default: x86_64', required=False, default="x86_64")
    parser.add_argument('-tag', metavar='string', type=str, help='Select container tag name, default: latest', required=False, default="latest")

    args = parser.parse_args()

    if not os.path.isfile(args.file):
        print('The file {} does not exist'.format(args.file))
        exit(1)
    return args


def read_job_project(in_file_name):
    with open(in_file_name) as in_file:
        data = yaml.safe_load(in_file)
    return data


def resolve_template(str, config, image):
    vars = {**config, **image}
    t = Template(str)

    if config.get('env'):
        t.template = t.safe_substitute({**config['env']})
        vars = {**config, **image, **config['env'], **os.environ}

    res = t.safe_substitute(vars)
    return res


def generate_pod_yaml(args):
    job_yaml = read_job_project(args.file)
    image = None

    for item in job_yaml['runs_on_dockers']:
        if args.image_name is None or args.image_name == item['name']:
            image = item
            break

    if image is None:
        print("Error: Image with name '{}' is not found".format(args.image_name))
        exit(1)

    image['tag'] = image.get('tag', args.tag)
    image['arch'] = image.get('arch', args.arch)

    uri = image.get('uri', image['arch'] + "/" + image['name'])
    url = image.get('url', job_yaml['registry_host'] + job_yaml['registry_path'] + "/" + uri + ":" + image['tag'])

    image['uri'] = resolve_template(uri, job_yaml, image)
    image['url'] = resolve_template(url, job_yaml, image)

    limits = '{' + job_yaml['kubernetes'].get('limits', '') + '}'
    requests = '{' + job_yaml['kubernetes'].get('requests', '') + '}'
    nodeSelector = job_yaml['kubernetes'].get('nodeSelector', nodeSelector_tmpl.format(os='linux', arch=arch_to_k8s_arch[image['arch']]))
    uid = image.get('uid', '0')
    gid = image.get('gid', '0')
    volumes = ''
    volumeMounts = ''
    for vid, volume in enumerate(job_yaml['volumes']):
        volumes += volumes_tmpl.format(
            hostPath=volume['hostPath'],
            volumeid=vid,
        )
        volumeMounts += mounts_tmpl.format(
            mountPath=volume['mountPath'],
            volumeid=vid
        )

    pod_yaml = template.format(
        podname=job_yaml['job'],
        imageurl=image['url'],
        imagename=image['name'].replace('.', ''),
        uid=uid,
        gid=gid,
        limits=limits,
        requests=requests,
        nodeSelector=nodeSelector,
        volumes=volumes,
        volumeMounts=volumeMounts
    )

    pod_yaml = "".join([s for s in pod_yaml.strip().splitlines(True) if s.strip()])
    if args.out is None:
        print(pod_yaml)
    else:
        with open(args.out, "w") as fout:
            print(pod_yaml, file=fout)


if __name__ == '__main__':
    args = usage()
    generate_pod_yaml(args)
