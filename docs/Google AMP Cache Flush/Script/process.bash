#!/bin/bash

CONTENTID="lifeandstyle/2020/dec/22/sex-at-christmas-tends-to-be-off-menu-until-fireworks-at-new-year-study"

private_key_file='private-key.pem'

if [[ ! -r ${private_key_file} ]]; then
  echo 'I do not have a private key or I can not read it.'
  echo 'Please specify private key with full path to private_key_file variable.'
  exit 1
fi

TIMESTAMP=`date +%s`
principal_fragment="/update-cache/c/s/amp.theguardian.com/${CONTENTID}?amp_action=flush&amp_ts=${TIMESTAMP}" 

echo -n ${principal_fragment} | openssl dgst -sha256 -sign ${private_key_file} > signature.bin
cat signature.bin | openssl base64 > signature.base64
signature=$(cat signature.base64 | tr -d "\t\n\r" | tr -- '+/' '-_' | sed -e 's/=//g') 
request_url="https://amp-theguardian-com.cdn.ampproject.org${principal_fragment}&amp_url_signature=${signature}"
echo ${request_url}
