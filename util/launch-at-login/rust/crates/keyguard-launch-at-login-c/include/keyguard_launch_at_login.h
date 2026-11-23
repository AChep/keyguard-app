#ifndef KEYGUARD_LAUNCH_AT_LOGIN_H
#define KEYGUARD_LAUNCH_AT_LOGIN_H
#include <stdint.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
uint32_t keyguard_launch_at_login_abi_version(void);
int32_t keyguard_launch_at_login_execute(int32_t operation, const uint8_t *request, size_t length);
size_t keyguard_launch_at_login_last_error(uint8_t *buffer, size_t capacity);
#ifdef __cplusplus
}
#endif
#endif
