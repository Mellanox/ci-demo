docker build -t harbor.mellanox.com/swx-infra/ci_demo_schema_validator .  \
&& docker push harbor.mellanox.com/swx-infra/ci_demo_schema_validator

echo "Took $SECONDS seconds to complete"

