import urllib.request
import json
import os
import sys
import time

TOKEN = os.environ.get('GITHUB_TOKEN') or os.environ.get('GH_TOKEN')
if not TOKEN:
    sys.exit('GITHUB_TOKEN is not set. Export a fine-grained PAT with Actions:read before running.')
HEADERS = {'Authorization': f'Bearer {TOKEN}', 'User-Agent': 'Colgram'}

def get_run(run_id):
    url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}'
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def get_jobs(run_id):
    url = f'https://api.github.com/repos/Kleisym/Colgram/actions/runs/{run_id}/jobs'
    req = urllib.request.Request(url, headers=HEADERS)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode('utf-8'))

def get_latest_release():
    url = 'https://api.github.com/repos/Kleisym/Colgram/releases/latest'
    req = urllib.request.Request(url, headers=HEADERS)
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except Exception as e:
        return None

def main():
    run_id = sys.argv[1] if len(sys.argv) > 1 else '35460583107'
    print(f"Monitoring GitHub Actions Run {run_id}...")
    
    start_time = time.time()
    last_step = ""

    while True:
        try:
            run_data = get_run(run_id)
            status = run_data.get('status')
            conclusion = run_data.get('conclusion')
            
            jobs_data = get_jobs(run_id)
            current_step = ""
            for job in jobs_data.get('jobs', []):
                for step in job.get('steps', []):
                    if step.get('status') == 'in_progress':
                        current_step = step.get('name')
                        break
            
            elapsed = int(time.time() - start_time)
            if current_step != last_step and current_step:
                print(f"[{elapsed}s] Current Step: {current_step}")
                last_step = current_step

            if status == 'completed':
                print(f"\nRun finished with conclusion: {conclusion}")
                if conclusion == 'success':
                    rel = get_latest_release()
                    if rel:
                        print(f"Release Tag: {rel.get('tag_name')}")
                        for asset in rel.get('assets', []):
                            print(f"  Asset: {asset.get('name')} -> {asset.get('browser_download_url')}")
                else:
                    for job in jobs_data.get('jobs', []):
                        for step in job.get('steps', []):
                            if step.get('conclusion') == 'failure':
                                print(f"  FAILED STEP: {step.get('name')}")
                break

        except Exception as e:
            print(f"Poll warning: {e}")

        time.sleep(20)

if __name__ == '__main__':
    main()
