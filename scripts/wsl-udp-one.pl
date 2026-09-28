use strict;
use warnings;
use IO::Socket::INET;

# One destination, one datagram, one bounded wait. The smallest thing that can answer the
# question, so a failure here is a failure of this file rather than of the probe around it.
my $sock = IO::Socket::INET->new(
    LocalAddr => '26.226.94.158',
    LocalPort => 0,
    PeerAddr  => '1.1.1.1',
    PeerPort  => 53,
    Proto     => 'udp',
) or die "socket: $!";

my $labels = join('', map { chr(length($_)) . $_ } split(/\./, 'cloudflare.com')) . "\0";
my $query  = pack('n6', 0x1234, 0x0100, 1, 0, 0, 0) . $labels . pack('n2', 1, 1);

$sock->send($query);
my $answer = $sock->recv(my $buf, 2048, 3);
if (defined $answer) {
    printf("1.1.1.1:53 answered %d bytes\n", length($buf));
} else {
    print "1.1.1.1:53 silent\n";
}
