#ifndef KEYGUARD_FIDO2_H
#define KEYGUARD_FIDO2_H
#include <stddef.h>
#include <stdint.h>
uint32_t keyguard_fido2_abi_version(void);
uint64_t keyguard_fido2_create(void);
void keyguard_fido2_cancel(uint64_t id);
void keyguard_fido2_close(uint64_t id);
size_t keyguard_fido2_execute(uint64_t id, const uint8_t *input, size_t length, uint8_t *output, size_t capacity);
#endif
