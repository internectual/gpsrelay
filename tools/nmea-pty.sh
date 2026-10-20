#!/bin/sh
# Expose GPS Relay's NMEA stream as a pseudo-terminal (pty) on this computer, using socat.
#
# The pty stays put while the phone comes and goes: the connection is retried every few seconds
# and the device path never changes, so gpsd, cgps, OpenCPN, `cat` and the like can keep it open.
#
#   nmea-pty.sh [-l LINK] tcp HOST[:PORT]   read the phone's NMEA TCP server (default port 10110)
#   nmea-pty.sh [-l LINK] udp [PORT]        receive the phone's NMEA UDP datagrams (default port 10110)
#
# Options:
#   -l LINK   symlink to create for the pty (default /tmp/gpsrelay-nmea)
#
# Examples:
#   nmea-pty.sh tcp 192.168.42.129             # phone USB-tethered to this computer
#   adb forward tcp:10110 tcp:10110 && nmea-pty.sh tcp localhost    # USB debugging, no tethering
#   nmea-pty.sh udp 10110                      # phone set to send UDP to this computer
#   gpsd -N -n /tmp/gpsrelay-nmea              # then point gpsd (or anything else) at the pty

set -eu

LINK=/tmp/gpsrelay-nmea
DEFAULT_PORT=10110

usage() {
    sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2
    exit 2
}

while getopts "l:h" opt; do
    case $opt in
        l) LINK=$OPTARG ;;
        *) usage ;;
    esac
done
shift $((OPTIND - 1))
[ $# -ge 1 ] || usage

if ! command -v socat >/dev/null 2>&1; then
    echo "socat is not installed (try: apt install socat, dnf install socat, brew install socat)" >&2
    exit 1
fi

is_port() { case $1 in '' | *[!0-9]*) return 1 ;; *) [ "$1" -ge 1 ] && [ "$1" -le 65535 ] ;; esac; }

mode=$1
shift
case $mode in
    tcp)
        [ $# -eq 1 ] || usage
        case $1 in
            *:*) host=${1%:*}; port=${1##*:} ;;
            *) host=$1; port=$DEFAULT_PORT ;;
        esac
        case $host in '' | *[!A-Za-z0-9._-]*) echo "invalid host: $host" >&2; exit 2 ;; esac
        is_port "$port" || { echo "invalid port: $port" >&2; exit 2; }
        # -T 10: give up on a connection that has gone silent for 10 s (cable pulled, tethering off).
        reader="while :; do socat -T 10 -u TCP:$host:$port,connect-timeout=5 - || true; sleep 2; done"
        ;;
    udp)
        [ $# -le 1 ] || usage
        port=${1:-$DEFAULT_PORT}
        is_port "$port" || { echo "invalid port: $port" >&2; exit 2; }
        reader="while :; do socat -u UDP-RECV:$port,reuseaddr - || true; sleep 2; done"
        ;;
    *) usage ;;
esac

echo "NMEA pty: $LINK (Ctrl-C to stop)" >&2
exec socat -d PTY,link="$LINK",raw,echo=0 SYSTEM:"$reader"
