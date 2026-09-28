use strict;
use warnings;
use IO::Socket::INET;

my $src = IO::Socket::INET->new(
    LocalAddr => '26.226.94.158',
    LocalPort => 0,
    Proto     => 'udp',
) or die "cannot bind to the Radmin address: $!";

# A real DNS query, so a control and a probe can be told apart.
my $labels = join('', map { chr(length($_)) . $_ } split(/\./, 'cloudflare.com')) . "\0";
# DNS header: id, flags, qdcount, ancount, nscount, arcount - each a network-order 16-bit value.
# Written as six explicit 'n' fields rather than 'nnnnNn', whose trailing "Nn" takes a 32-bit
# argument and then reads the *next* one as a string, so the counts came out as control characters
# and pack failed with "isn't numeric" before a single byte left the machine.
my $query  = pack('n6', 0x1234, 0x0100, 1, 0, 0, 0) . $labels . pack('n2', 1, 1);

my @targets = (
    ['1.1.1.1',           53, 'a public resolver - the control'],
    ['162.159.192.1',   2408, 'WARP WireGuard ingress'],
    ['162.159.192.1',    443, 'same ingress on 443'],
    ['162.159.198.2',    443, 'WARP MASQUE ingress'],
    ['188.114.96.1',    2408, 'the other WireGuard ingress'],
);

for my $t (@targets) {
    my ($ip, $port, $who) = @$t;
    # One connected socket per target. IO::Socket::INET's send takes flags in its third argument,
    # not a packed sockaddr, so passing one there fails in pack long before a byte leaves the
    # machine - and an unconnected socket would need the address assembled by hand anyway.
    # Bound to the Radmin address deliberately. The same destinations are also routed through the
    # host's WireGuard tunnel, so leaving the source unbound would measure that tunnel a second time
    # and call it a second path. `ip route get 162.159.192.1` in this distro answers
    # "via 100.127.255.1 dev eth5", which is the tunnel - so anything measured from here has to say
    # which interface it left by, or the result is the same measurement wearing a new label.
    my $one = IO::Socket::INET->new(
        LocalAddr => '26.226.94.158',
        LocalPort => 0,
        PeerAddr  => $ip,
        PeerPort  => $port,
        Proto     => 'udp',
    );
    if (!$one) {
        printf("  %-16s udp/%-5d %-9s  %s\n", $ip, $port, 'no socket', $who);
        next;
    }
    $one->send($query);
    # A SIGALRM around the read rather than a recv timeout. On this path the read does not come
    # back on its own: the datagram is dropped somewhere that never sends an ICMP either, and a
    # recv with a timeout still blocked past it. An alarm interrupts the syscall from outside,
    # which is the only thing that ends the wait - and a probe that cannot be interrupted is a
    # probe that hangs rather than reporting.
    # select() sets the read timeout itself, with no module and no signal: it is in the kernel and
    # present on a minimal install. Time::HiRes and alarm are not - an earlier attempt at this
    # failed to compile, which is the sixth time in this project that a missing piece of the
    # environment produced a failure that looked like a network result.
    my $rin = '';
    vec($rin, fileno($one), 1) = 1;
    my $ready = select(my $rout = $rin, undef, undef, 3.0);
    my $answer;
    my $buf;
    if ($ready && $ready > 0) {
        $answer = $one->recv(my $buf, 2048);
    }
    printf("  %-16s udp/%-5d %-9s  %s\n", $ip, $port,
        defined($answer) ? (length($buf) . 'B') : 'silent', $who);
}


