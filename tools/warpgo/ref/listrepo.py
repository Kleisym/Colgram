import urllib.request, json
req=urllib.request.Request('https://api.github.com/repos/Diniboy1123/connect-ip-go', headers={'User-Agent':'colgram-dev'})
meta=json.loads(urllib.request.urlopen(req, timeout=20).read())
print('default', meta['default_branch'])
url='https://api.github.com/repos/Diniboy1123/connect-ip-go/git/trees/%s?recursive=1'%meta['default_branch']
req=urllib.request.Request(url, headers={'User-Agent':'colgram-dev'})
data=json.loads(urllib.request.urlopen(req, timeout=20).read())
for t in data['tree']:
    if t['path'].endswith('.go'):
        print(t['path'])
