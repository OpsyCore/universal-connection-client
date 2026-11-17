#!/usr/bin/python3
"""Restricted, unprivileged first-pass VPS audit. Output is intended for public CI logs.

Only fixed labels, selected ports, bind classes, transport/security enums and
client counts are emitted. No config, user identifiers, keys, addresses, paths,
logs, or exception details are emitted. It never changes Marzban or Xray.
"""
import ipaddress
import json
import subprocess

PORTS = {443, 8000, 8443, 2052, 2053, 2083, 1080}
LABELS = {
    'VLESS WS TLS': 'vless-ws',
    'VMess WS TLS': 'vmess-ws',
    'VLESS WS Direct Plain': 'vless-plain',
}


def enum(value, allowed):
    return value if isinstance(value, str) and value in allowed else 'other'


def bind_class(value):
    value = str(value or '').strip('[]')
    if value in ('', '*', '0.0.0.0', '::'):
        return 'wildcard'
    try:
        return 'loopback' if ipaddress.ip_address(value).is_loopback else 'other'
    except ValueError:
        return 'other'


def main():
    try:
        result = subprocess.run(
            ['/usr/bin/ss', '-H', '-lntu'], capture_output=True, text=True,
            timeout=10, check=True,
        )
        for line in result.stdout.splitlines():
            fields = line.split()
            if len(fields) < 5 or fields[0] not in ('tcp', 'udp'):
                continue
            local = fields[4]
            address, sep, port = local.rpartition(':')
            if sep and port.isdigit() and int(port) in PORTS:
                print('LISTENER protocol={} port={} bind={}'.format(
                    fields[0], int(port), bind_class(address)))
        print('LISTENERS done')
    except (OSError, subprocess.SubprocessError):
        print('LISTENERS unavailable')

    try:
        with open('/var/lib/marzban/xray_config.json', encoding='utf-8') as f:
            config = json.load(f)
        inbounds = config.get('inbounds', [])
        if not isinstance(inbounds, list):
            raise ValueError('invalid inbounds')
        for inbound in inbounds:
            if not isinstance(inbound, dict):
                continue
            port = inbound.get('port')
            port = port if type(port) is int and 1 <= port <= 65535 else 0
            stream = inbound.get('streamSettings') or {}
            settings = inbound.get('settings') or {}
            stream = stream if isinstance(stream, dict) else {}
            settings = settings if isinstance(settings, dict) else {}
            clients = settings.get('clients')
            count = min(len(clients), 1000000) if isinstance(clients, list) else -1
            print('FILE label={} protocol={} port={} bind={} network={} security={} clients={}'.format(
                LABELS.get(inbound.get('tag'), 'other'),
                enum(inbound.get('protocol'), {'vless', 'vmess', 'shadowsocks', 'trojan'}),
                port, bind_class(inbound.get('listen')),
                enum(stream.get('network', 'tcp'), {'tcp', 'raw', 'ws', 'grpc', 'xhttp'}),
                enum(stream.get('security', 'none'), {'none', 'tls', 'reality'}), count))
        print('FILE done')
    except (OSError, ValueError, TypeError, AttributeError):
        print('FILE unavailable')


if __name__ == '__main__':
    main()
