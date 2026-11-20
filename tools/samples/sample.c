extern int puts(const char*);
extern void *dlopen(const char*, int);
int g_counter = 42;
static int helper(int x){ return x*3+1; }
__attribute__((weak)) int weak_fn(void){ return 7; }
const char *api_url = "https://api.example.com/v1/data";
int Java_com_example_Native_add(void *env, void *thiz, int a, int b){ return helper(a)+b+g_counter; }
int exported_fn(int a){ puts("hello from native"); dlopen("libfoo.so",0); return a+weak_fn(); }
