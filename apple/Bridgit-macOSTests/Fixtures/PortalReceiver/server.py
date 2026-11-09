from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
class H(BaseHTTPRequestHandler):
 def do_GET(self):
  with open('/tmp/br-mac-browser-receiver/requests.txt','a') as f: f.write(self.path+'\n')
  body=('<html><body>'+('ALPHA DETAIL FIXTURE' if 'detail=1' in self.path else 'ALPHA ROOT FIXTURE <a href="?detail=1#pkg.alpha-test">Open detail fixture</a>')+'</body></html>').encode()
  self.send_response(200); self.send_header('Content-Type','text/html'); self.send_header('Content-Length',str(len(body))); self.end_headers(); self.wfile.write(body)
ThreadingHTTPServer(('127.0.0.1',18347),H).serve_forever()
