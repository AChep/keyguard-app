#ifndef KEYGUARD_AGE_H
#define KEYGUARD_AGE_H
#include <stddef.h>
#include <stdint.h>
uint32_t keyguard_age_abi_version(void);
uint64_t keyguard_age_create(void);
void keyguard_age_close(uint64_t id);
size_t keyguard_age_execute(uint64_t id, const uint8_t *input, size_t length, uint8_t *output, size_t capacity);
#endif
