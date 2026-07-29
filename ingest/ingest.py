import boto3
import requests

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


        body = url_prefix + key
        url = "http://localhost:8080/ingest/doc"
        print(body)
        
        requests.put(url, data=body, headers={"Content-Type": "text/plain"})