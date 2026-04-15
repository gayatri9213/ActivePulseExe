#include <windows.h>
#include <wtsapi32.h>
#include <lmcons.h>
#include <sddl.h>
#include <iostream>
#include <string>

#pragma comment(lib, "wtsapi32.lib")
#pragma comment(lib, "advapi32.lib")

extern "C" {

/**
 * Gets the current Windows user name from the active console session.
 * This correctly detects the logged-in user even when running in admin context.
 */
JNIEXPORT jstring JNICALL Java_com_activepulse_agent_util_WindowsUserDetector_getCurrentWindowsUser(JNIEnv* env, jclass clazz) {
    DWORD sessionId = WTSGetActiveConsoleSessionId();
    if (sessionId == 0xFFFFFFFF) {
        // No active console session, try current process session
        sessionId = GetCurrentProcessId();
    }
    
    LPTSTR username = NULL;
    DWORD size = 0;
    
    // Try to get user from the active session
    if (WTSQuerySessionInformation(WTS_CURRENT_SERVER_HANDLE, sessionId, WTSUserName, &username, &size)) {
        if (username != NULL) {
            std::wstring wUsername(username);
            WTSFreeMemory(username);
            
            // Convert to UTF-8 for Java
            int utf8Size = WideCharToMultiByte(CP_UTF8, 0, wUsername.c_str(), -1, NULL, 0, NULL, NULL);
            if (utf8Size > 0) {
                char* utf8Str = new char[utf8Size];
                WideCharToMultiByte(CP_UTF8, 0, wUsername.c_str(), -1, utf8Str, utf8Size, NULL, NULL);
                jstring result = env->NewStringUTF(utf8Str);
                delete[] utf8Str;
                return result;
            }
        }
    }
    
    // Fallback: Get current process user
    DWORD usernameSize = UNLEN + 1;
    char fallbackUsername[UNLEN + 1];
    if (GetUserNameA(fallbackUsername, &usernameSize)) {
        return env->NewStringUTF(fallbackUsername);
    }
    
    return NULL;
}

/**
 * Gets the current Windows domain/AD domain for the user.
 */
JNIEXPORT jstring JNICALL Java_com_activepulse_agent_util_WindowsUserDetector_getCurrentWindowsDomain(JNIEnv* env, jclass clazz) {
    DWORD sessionId = WTSGetActiveConsoleSessionId();
    if (sessionId == 0xFFFFFFFF) {
        sessionId = GetCurrentProcessId();
    }
    
    LPTSTR domain = NULL;
    DWORD size = 0;
    
    // Try to get domain from the active session
    if (WTSQuerySessionInformation(WTS_CURRENT_SERVER_HANDLE, sessionId, WTSDomainName, &domain, &size)) {
        if (domain != NULL) {
            std::wstring wDomain(domain);
            WTSFreeMemory(domain);
            
            // Convert to UTF-8 for Java
            int utf8Size = WideCharToMultiByte(CP_UTF8, 0, wDomain.c_str(), -1, NULL, 0, NULL, NULL);
            if (utf8Size > 0) {
                char* utf8Str = new char[utf8Size];
                WideCharToMultiByte(CP_UTF8, 0, wDomain.c_str(), -1, utf8Str, utf8Size, NULL, NULL);
                jstring result = env->NewStringUTF(utf8Str);
                delete[] utf8Str;
                return result;
            }
        }
    }
    
    // Fallback: Get current process domain
    LPSTR userSid = NULL;
    SID_NAME_USE sidUse;
    DWORD domainSize = 0;
    DWORD sidSize = 0;
    
    // First call to get required buffer sizes
    LookupAccountNameA(NULL, NULL, NULL, &sidSize, NULL, &domainSize, &sidUse);
    
    if (sidSize > 0 && domainSize > 0) {
        userSid = (LPSTR)LocalAlloc(LPTR, sidSize);
        char* domainName = new char[domainSize];
        
        if (LookupAccountNameA(NULL, NULL, userSid, &sidSize, domainName, &domainSize, &sidUse)) {
            jstring result = env->NewStringUTF(domainName);
            delete[] domainName;
            LocalFree(userSid);
            return result;
        }
        
        delete[] domainName;
        LocalFree(userSid);
    }
    
    return NULL;
}

/**
 * Gets the current session ID for the active user session.
 */
JNIEXPORT jint JNICALL Java_com_activepulse_agent_util_WindowsUserDetector_getCurrentSessionId(JNIEnv* env, jclass clazz) {
    DWORD sessionId = WTSGetActiveConsoleSessionId();
    if (sessionId == 0xFFFFFFFF) {
        // No active console session, get current process session ID
        DWORD currentSessionId;
        if (ProcessIdToSessionId(GetCurrentProcessId(), &currentSessionId)) {
            return (jint)currentSessionId;
        }
        return -1;
    }
    return (jint)sessionId;
}

/**
 * Checks if the current user session is active (not locked).
 */
JNIEXPORT jboolean JNICALL Java_com_activepulse_agent_util_WindowsUserDetector_isUserSessionActive(JNIEnv* env, jclass clazz) {
    // Check if the workstation is locked
    HDESK hDesk = OpenInputDesktop(0, FALSE, DESKTOP_READOBJECTS);
    if (hDesk == NULL) {
        // Cannot open input desktop, likely locked
        return JNI_FALSE;
    }
    
    CloseDesktop(hDesk);
    
    // Additional check: verify if screen saver is running
    BOOL isScreenSaverActive = FALSE;
    SystemParametersInfoA(SPI_GETSCREENSAVERRUNNING, 0, &isScreenSaverActive, 0);
    
    if (isScreenSaverActive) {
        return JNI_FALSE;
    }
    
    return JNI_TRUE;
}

/**
 * Gets an array of all currently logged-in users.
 */
JNIEXPORT jobjectArray JNICALL Java_com_activepulse_agent_util_WindowsUserDetector_getLoggedInUsers(JNIEnv* env, jclass clazz) {
    WTS_SESSION_INFO* pSessionInfo = NULL;
    DWORD count = 0;
    
    // Enumerate all sessions
    if (WTSEnumerateSessions(WTS_CURRENT_SERVER_HANDLE, 0, 1, &pSessionInfo, &count)) {
        // Create Java string array
        jclass stringClass = env->FindClass("java/lang/String");
        jobjectArray result = env->NewObjectArray(count, stringClass, NULL);
        
        int validUsers = 0;
        for (DWORD i = 0; i < count; i++) {
            if (pSessionInfo[i].State == WTSActive || pSessionInfo[i].State == WTSConnected) {
                LPTSTR username = NULL;
                DWORD size = 0;
                
                if (WTSQuerySessionInformation(WTS_CURRENT_SERVER_HANDLE, pSessionInfo[i].SessionId, 
                                             WTSUserName, &username, &size)) {
                    if (username != NULL) {
                        std::wstring wUsername(username);
                        WTSFreeMemory(username);
                        
                        // Convert to UTF-8 for Java
                        int utf8Size = WideCharToMultiByte(CP_UTF8, 0, wUsername.c_str(), -1, NULL, 0, NULL, NULL);
                        if (utf8Size > 0) {
                            char* utf8Str = new char[utf8Size];
                            WideCharToMultiByte(CP_UTF8, 0, wUsername.c_str(), -1, utf8Str, utf8Size, NULL, NULL);
                            jstring jUsername = env->NewStringUTF(utf8Str);
                            env->SetObjectArrayElement(result, validUsers, jUsername);
                            delete[] utf8Str;
                            validUsers++;
                        }
                    }
                }
            }
        }
        
        // Resize array to valid users count
        if (validUsers < count) {
            jobjectArray trimmedResult = env->NewObjectArray(validUsers, stringClass, NULL);
            for (int i = 0; i < validUsers; i++) {
                jstring user = (jstring)env->GetObjectArrayElement(result, i);
                env->SetObjectArrayElement(trimmedResult, i, user);
            }
            result = trimmedResult;
        }
        
        WTSFreeMemory(pSessionInfo);
        return result;
    }
    
    // Return empty array if enumeration fails
    jclass stringClass = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, stringClass, NULL);
}

} // extern "C"
