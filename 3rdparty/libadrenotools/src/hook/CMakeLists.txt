add_library(hook_impl SHARED hook_impl.cpp hook_impl.h hook_impl_params.h)

target_compile_options(hook_impl PRIVATE -Wall -Wextra)
target_link_libraries(hook_impl linkernsbypass log c++_shared)
target_include_directories(hook_impl PRIVATE ../../include)
set_target_properties(hook_impl PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(main_hook SHARED main_hook.c)

target_compile_options(main_hook PRIVATE -Wall -Wextra)
target_link_options(main_hook PRIVATE -z global)
target_link_libraries(main_hook hook_impl)
set_target_properties(main_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(file_redirect_hook SHARED file_redirect_hook.c)

target_compile_options(file_redirect_hook PRIVATE -Wall -Wextra)
target_link_options(file_redirect_hook PRIVATE -z global)
target_link_libraries(file_redirect_hook hook_impl)
set_target_properties(file_redirect_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)

add_library(gsl_alloc_hook SHARED gsl_alloc_hook.c)

target_compile_options(gsl_alloc_hook PRIVATE -Wall -Wextra)
target_link_options(gsl_alloc_hook PRIVATE -z global)
target_link_libraries(gsl_alloc_hook hook_impl)
set_target_properties(gsl_alloc_hook PROPERTIES CXX_VISIBILITY_PRESET hidden)

# Android 15+ devices may use 16 KB pages; every shared library loaded at runtime must
# carry 16 KB-aligned LOAD segments for the linker to accept it. libMobileGL already
# links with -z max-page-size=16384, but these hook libraries are loaded separately by
# libadrenotools, so give them the same alignment.
if (ANDROID)
    foreach(_adrenotools_hook_target hook_impl main_hook file_redirect_hook gsl_alloc_hook)
        target_link_options(${_adrenotools_hook_target} PRIVATE -Wl,-z,max-page-size=16384)
    endforeach()
endif()
