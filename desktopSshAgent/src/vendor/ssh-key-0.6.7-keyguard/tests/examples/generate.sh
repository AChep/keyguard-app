# Generator script for test cases
#
# This shouldn't ever need to be run again, but automates and documents how the
# test vectors in this directory were created.

set -eux

ssh-keygen -t dsa -f id_dsa_1024 -C user@example.com
ssh-keygen -t ecdsa -b 256 -f id_ecdsa_p256 -C user@example.com
ssh-keygen -t ecdsa -b 384 -f id_ecdsa_p384 -C user@example.com
ssh-keygen -t ecdsa -b 521 -f id_ecdsa_p521 -C user@example.com
ssh-keygen -t ed25519 -f id_ed25519 -C user@example.com
ssh-keygen -t rsa -b 3072 -f id_rsa_3072 -C user@example.com
ssh-keygen -t rsa -b 4096 -f id_rsa_4096 -C user@example.com
