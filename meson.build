project('hello', 'c',
  version : '0.1',
  default_options : ['warning_level=3'])

cc = meson.get_compiler('c')
m_dep = cc.find_library('m', required : false)

incdir = include_directories(['src/libhello'])

hello_src = ['src/hello/main.c']
libhello_src = ['src/libhello/hello.c', 'src/libhello/hello.h']

lib = shared_library('hello', libhello_src)
exe = executable('hello', sources: hello_src, link_with: lib, include_directories: incdir, dependencies : m_dep, install : true)

rpm = import('rpm')
rpm.generate_spec_template()
