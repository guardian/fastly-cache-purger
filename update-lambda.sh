#!/usr/bin/env bash
#
# Builds and deploys the Lambda

set -e

my_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

sbt assembly

jar_file=$(echo $my_dir/target/scala-2.11/fastly-cache-purger-assembly*.jar)

aws lambda update-function-code \
  --function-name fastly-cache-purger \
  --zip-file fileb://$jar_file
