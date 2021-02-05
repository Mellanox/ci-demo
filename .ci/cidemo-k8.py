#!/usr/bin/env python3

import os
import sys
import argparse
import yaml
from string import Template

template = """apiVersion: v1
kind: Pod
metadata:
  name: "{podname}"
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
    hostNetwork: true
    tty: true
    volumeMounts:
    {volumeMounts}
  nodeSelector:
    {node_selector}
  restartPolicy: "Never"
  securityContext:
    runAsGroup: {uid}
    runAsUser: {gid}
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

def usage() :
	parser = argparse.ArgumentParser(description='Generate pod.yaml from ci-demo project file')
	parser.add_argument('-file', metavar='filename', type=str, help='Path to job_matrix.yaml file', required=True)
	parser.add_argument('-out',  metavar='filename', type=str, help='Output file for pod.yaml file, default: stdout', required=False)
	parser.add_argument('-image_name', metavar='string', type=str, help="Select container name: default: find 1st from 'runs_on_dockers' list", required=False)
	parser.add_argument('-arch', metavar='string', type=str, help='Select container arch, default: x86_64', required=False, default="x86_64")
	parser.add_argument('-tag', metavar='string', type=str, help='Select container tag name, default: latest', required=False, default="latest")

	args = parser.parse_args()

	if not os.path.isfile(args.file):
		print('The file %s does not exist' % args.file)
		sys.exit()
	return args

def readJobProject(inFileName):
	with open(inFileName) as inFile:
		data = yaml.load(inFile)
	return data

def resolveTemplate(str, config, image):
	vars = {**config,**image}
	t = Template(str)

	if config.get('env'):
		t.template = t.safe_substitute({**config['env']})
		vars = {**config,**image,**config['env']}

	res = t.safe_substitute(vars)
	return res

def getYamlVal(yaml, key, default):
	val = yaml.get(key)
	if val:
		return val
	return default

def generatePodYaml(args):
	job_yaml = readJobProject(args.file)
	image = None

	for item in job_yaml['runs_on_dockers']:
		if  args.image_name is None or args.image_name == item['name']:
			image = item
			break

	if image is None:
		print("Error: Image with name '" + args.image_name + "' is not found")
		sys.exit()
	
	image['tag']  = getYamlVal(image, 'tag', args.tag)
	image['arch'] = getYamlVal(image, 'arch', args.arch)

	uri = getYamlVal(image, 'uri', image['arch'] + "/" + image['name'])
	url = getYamlVal(image, 'url', job_yaml['registry_host'] + job_yaml['registry_path'] + "/" + uri + ":" + image['tag'])

	image['uri'] = resolveTemplate(uri, job_yaml, image)
	image['url'] = resolveTemplate(url, job_yaml, image)
	
	limits   = getYamlVal(job_yaml['kubernetes'], 'limits', '')
	requests = getYamlVal(job_yaml['kubernetes'], 'requests', '')
	nodeSelector = getYamlVal(job_yaml['kubernetes'], 'nodeSelector', '')
	uid = getYamlVal(image, 'uid', '0')
	gid = getYamlVal(image, 'gid', '0')
	volumes = ''
	volumeMounts = ''
	vid = 0
	for volume in job_yaml['volumes']:
		volumes += volumes_tmpl.format(	
										hostPath=volume['hostPath'],
										volumeid=vid,
										)
		volumeMounts += volumeMounts.format( 	
										mountPath=volume['mountPath'],
										volumeid=vid
										)
		vid += 1

	pod_yaml = template.format(	podname=job_yaml['job'], 
								imageurl=image['url'], 
								imagename=image['name'], 
								uid=uid, 
								gid=gid,
								limits=limits,
								requests=requests,
								node_selector=nodeSelector,
								volumes=volumes,
								volumeMounts=volumeMounts
								)

	pod_yaml = "".join([s for s in pod_yaml.strip().splitlines(True) if s.strip()])
	if args.out is None:
		fout = sys.stdout
	else:
		fout = open(args.out, "w")

	print(pod_yaml, file=fout)

def main():
	args = usage()
	generatePodYaml(args)

if __name__ == '__main__':
    main()
