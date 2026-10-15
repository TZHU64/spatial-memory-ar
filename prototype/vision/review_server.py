#!/usr/bin/env python3
"""Local review UI and evaluator-only event labels. Never calls a model or VLM."""
import argparse
import json
import math
import mimetypes
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import re
import threading
from urllib.parse import unquote, urlparse

from evaluate import evaluate, score

HERE = Path(__file__).resolve().parent


def make_server(output, annotations, port):
    output = output.resolve()
    report = json.loads((output/"report.json").read_text())
    source = Path(report["source_folder"])
    lock = threading.Lock()

    def labels():
        if annotations.exists():
            return json.loads(annotations.read_text())
        return {"schema_version":1,"run_id":report["run_id"],"task":report["task"],
                "review_complete":False,"events":[]}

    class Handler(BaseHTTPRequestHandler):
        def valid_host(self):
            return self.headers.get("Host") in (f"127.0.0.1:{self.server.server_port}",f"localhost:{self.server.server_port}")

        def json(self, value, status=200):
            data = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type","application/json; charset=utf-8")
            self.send_header("Content-Length",str(len(data)))
            self.send_header("Cache-Control","no-store")
            self.send_header("X-Content-Type-Options","nosniff")
            self.end_headers()
            self.wfile.write(data)

        def file(self,path,head=False):
            if not path.is_file(): self.send_error(404); return
            size = path.stat().st_size
            start,end = 0,size-1
            ranged = self.headers.get("Range")
            if ranged:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)",ranged)
                if not match or not any(match.groups()): self.send_error(416); return
                a,b = match.groups()
                if a:
                    start = int(a); end = min(int(b),size-1) if b else size-1
                else:
                    start = max(0,size-int(b))
                if start > end or start >= size:
                    self.send_response(416); self.send_header("Content-Range",f"bytes */{size}"); self.end_headers(); return
            self.send_response(206 if ranged else 200)
            self.send_header("Content-Type",mimetypes.guess_type(str(path))[0] or "application/octet-stream")
            self.send_header("Content-Length",str(end-start+1))
            self.send_header("Accept-Ranges","bytes")
            self.send_header("X-Content-Type-Options","nosniff")
            if ranged: self.send_header("Content-Range",f"bytes {start}-{end}/{size}")
            self.end_headers()
            if not head:
                with path.open("rb") as video:
                    video.seek(start)
                    remaining = end-start+1
                    try:
                        while remaining:
                            block = video.read(min(256*1024,remaining))
                            if not block: break
                            self.wfile.write(block); remaining -= len(block)
                    except (BrokenPipeError,ConnectionResetError): pass

        def do_HEAD(self): self.get(True)
        def do_GET(self): self.get(False)

        def get(self,head):
            if not self.valid_host(): self.send_error(403); return
            path = unquote(urlparse(self.path).path)
            if path == "/api/state":
                document = labels()
                metrics = evaluate(output,annotations) if annotations.exists() else None
                self.json({"report":report,"annotations":document,"metrics":metrics,
                           "decisions":[json.loads(line) for line in (output/"decisions.jsonl").read_text().splitlines()],
                           "timeline":json.loads((output/"timeline.json").read_text())})
            elif path in ("/media/vga.mp4","/media/rgb.mp4"):
                self.file(source/Path(path).name,head)
            elif path.startswith("/assets/") and re.fullmatch(r"(?:vga|rgb)_\d+\.jpg",path[8:]):
                self.file(output/"assets"/path[8:],head)
            elif path in ("/","/app.js","/style.css"):
                self.file(HERE/"web"/("index.html" if path=="/" else path[1:]),head)
            else: self.send_error(404)

        def do_POST(self):
            if not self.valid_host(): self.send_error(403); return
            origin = self.headers.get("Origin")
            if origin and origin != f"http://{self.headers.get('Host')}": self.send_error(403); return
            if self.path != "/api/annotations": self.send_error(404); return
            try:
                size = int(self.headers.get("Content-Length","0"))
                if not 0 < size <= 256*1024: raise ValueError("Invalid annotation size.")
                document = json.loads(self.rfile.read(size))
                if document.get("run_id") != report["run_id"]: raise ValueError("Wrong recording.")
                events = document.get("events")
                if not isinstance(events,list) or len(events)>2000: raise ValueError("Invalid events.")
                if type(document.get("review_complete")) is not bool: raise ValueError("Invalid review status.")
                for event in events:
                    if not isinstance(event,dict) or event.get("importance") not in ("required","optional","ignore"):
                        raise ValueError("Invalid event importance.")
                    for field in ("start","end"):
                        value = event.get(field)
                        if type(value) not in (int,float) or not math.isfinite(value): raise ValueError("Invalid event time.")
                    if not isinstance(event.get("label"),str) or not 1 <= len(event["label"].strip()) <= 500:
                        raise ValueError("Add a short description to each event.")
                score(events,[],*report["interval"])
                clean = {"schema_version":1,"run_id":report["run_id"],"task":report["task"],
                         "review_complete":document["review_complete"],"events":events}
                with lock:
                    annotations.parent.mkdir(parents=True,exist_ok=True)
                    temporary = annotations.with_suffix(".tmp")
                    temporary.write_text(json.dumps(clean,indent=2)+"\n")
                    temporary.replace(annotations)
                    metrics = evaluate(output,annotations)
                    (output/"evaluation.json").write_text(json.dumps(metrics,indent=2)+"\n")
                self.json({"annotations":clean,"metrics":metrics})
            except (ValueError,KeyError,TypeError) as error:
                self.json({"error":str(error)},400)

    return ThreadingHTTPServer(("127.0.0.1",port),Handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output",type=Path)
    parser.add_argument("--port",type=int,default=8770)
    parser.add_argument("--annotations",type=Path)
    args = parser.parse_args()
    report = json.loads((args.output/"report.json").read_text())
    annotations = args.annotations or HERE.parents[1]/"data/evaluator_only"/report["run_id"]/"events.json"
    server = make_server(args.output,annotations,args.port)
    print(f"Review: http://127.0.0.1:{server.server_port} · Labels: {annotations}",flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()
