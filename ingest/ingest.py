import boto3
import gzip
import orjson

session = boto3.Session(profile_name='surf')
s3_client = session.client('s3')

url_prefix = "https://objectstore.surf.nl/87435b768620494e8e911c83d1997f24:globalise-data/" 

paginator = s3_client.get_paginator('list_objects_v2')
page_iterator = paginator.paginate(
    Bucket='globalise-data',
    Prefix='objects/inventory/'
)

objects = []
for response in page_iterator:
    objects.extend(response.get('Contents', []))

for content in objects:
    key = content['Key']

    if key.endswith('.index.json'):
        print(url_prefix + key)