
In Feb 2021, we have upgraded the way AMP cache purge (cache flush) requests are sent to the Google AMP cache, in line with the instructions here: [https://developers.google.com/amp/cache/update-cache](https://developers.google.com/amp/cache/update-cache)

This contains notes for future reference.

### URL signing from first principles

The **Script** folder contains one file: `process.bash`. This bash script performs the signature from first principles. This script is only given for reference (simply as part of this documentation) and in case you want / need to send a request manually, or double check how the singing process works. To use: 

- Update the script with the correct CONTENTID
- Download the PEM private key (`.pem`) from the CAPI account (write the file as `private-key.pem`) 	
- run `./process.bash`

Note that the process generates two files (`signature.bin` and `signature.base64`) as side effect in addition of its output. 

An example of output is: 

https://amp-theguardian-com.cdn.ampproject.org/update-cache/c/s/amp.theguardian.com/lifeandstyle/2020/dec/22/sex-at-christmas-tends-to-be-off-menu-until-fireworks-at-new-year-study?amp_action=flush&amp_ts=1612184130&amp_url_signature=iqakxU200DNnz5UBSSg_SF2XWdRZPufRsItRQnlMmOU-iBE54ipSUOyPfZV40u6BKP4wwAcdp-dzu4b2DxhjATOkVBdUeTbdfVicpkLhSznq_OBMjKcCxFSwTIJSD2zyRgWgJxmUy6PrwkIWylR-v8eB8DTPPB5Q68pXQyAq44_-qF5Tkzg-b4rbfnFbTNKILl0R53Ug6lUTTX5LaS8Yuor4cCzMx6Bz1ZJpjP-ycu1g6_eWON-E1LdZP_kJDkYyAZGt5Sdp_dRZ6e-kxqz8Kyr20LruWQeoxgjRr2tlEZWqf1RA4ikH-AgJY6voGJ2Fkmimto7_HOXviN7hgwgXwA

You can then `curl` this URL and the AMP cache should returns "OK" (200).

Note that when curling the url, do not, if those characters are present, include any underscore(s) appearing at the and the url. In other words, if the output of the script is 

```
https://apm(...)QfhsSAs__
```

You should 

```
curl "https://apm(...)QfhsSAs"
```

### Why are there two private keys in the CAPI S3 bucket ?

The `.pem` one is the one generated when the public/private pair was made (and the one used by the bash script). The `.der` one is the one used by the lambda. They are the same private key, just in different formats.

### The private key is leaked, what do I do?

Don't panic. Here is what to do:

[1] Issue a new key pair

```
openssl genrsa 2048 > private-key.pem
openssl rsa -in private-key.pem -pubout >public-key.pem
```

[2] Generate the private key in `.der` format.

```
openssl pkcs8 -topk8 -inform PEM -outform DER -in private-key.pem -out private-key.der -nocrypt
```

[3] Upload the private key (`.der`) to the CAPI account (

- S3 bucket [ask the CAPI team for right name]
- filename `amp-flusher-private-key.der`

) at the same time contact the frontend team and ask them to update the public key.


 