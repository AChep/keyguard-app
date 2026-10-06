#ifndef KEYGUARD_YUBIKEY_H
#define KEYGUARD_YUBIKEY_H
#include <stddef.h>
#include <stdint.h>
uint32_t keyguard_yubikey_abi_version(void);
uint64_t keyguard_yubikey_create(void);
void keyguard_yubikey_cancel(uint64_t id);
void keyguard_yubikey_close(uint64_t id);
size_t keyguard_yubikey_execute(uint64_t id, const uint8_t *input, size_t length, uint8_t *output, size_t capacity);
#endif
