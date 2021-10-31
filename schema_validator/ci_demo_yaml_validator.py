import yamale
from yamale import YamaleError
import sys
import os

script_root = os.path.dirname(os.path.abspath(__file__))
yaml_file_to_validate = None
try:
    if os.path.isfile(sys.argv[1]):
        yaml_file_to_validate = sys.argv[1]
    else:
        raise
except:
    print("This script expects first parameter to be a YAML schema file")
    exit(1)

schema_file = script_root + '/ci_demo_schema.yaml'

schema = yamale.make_schema(schema_file)
data = yamale.make_data(yaml_file_to_validate)
try:
    yamale.validate(schema, data)
    print('CI-Demo {} successfuly validated!'.format(yaml_file_to_validate))
except YamaleError as e:
    print('CI-Demo YAML Validation failed!')
    print(e)
    exit(1)