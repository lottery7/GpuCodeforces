set -eu

nginx -t
nginx
trap 'nginx -s quit' EXIT

for route in / /problems /problem/example /submissions /submission/example \
    /contests /contests/new /contest/example /contest/example/edit \
    /rating /profile /login /register; do
    wget -q -T 5 -O /tmp/page "http://127.0.0.1$route"
    cmp /tmp/page /usr/share/nginx/html/index.html
done

entry_assets=$(grep -oE '/assets/[^" ]+\.(js|css)' /usr/share/nginx/html/index.html)
for asset in $entry_assets \
    /fonts/JetBrainsMono-Regular.woff2 /favicon.svg; do
    wget -q -T 5 -O /tmp/asset "http://127.0.0.1$asset"
    cmp /tmp/asset "/usr/share/nginx/html$asset"
done

if wget -S -T 5 -O /tmp/missing http://127.0.0.1/assets/missing.js 2>/tmp/missing-headers; then
    echo 'Missing assets must return 404'
    exit 1
fi
grep -q 'HTTP/1.1 404' /tmp/missing-headers

if wget -S -T 5 -O /tmp/api http://127.0.0.1/api/session 2>/tmp/api-headers; then
    echo 'API requests must reach the unavailable backend'
    exit 1
fi
grep -q 'HTTP/1.1 502' /tmp/api-headers

echo 'Frontend container checks passed'
