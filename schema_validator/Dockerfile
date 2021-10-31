FROM python:3.6-alpine
RUN python -m pip install yamale
RUN mkdir /opt/schema_validator
COPY ci_demo_schema.yaml ci_demo_yaml_validator.py /opt/schema_validator/
