class Test {

public:
int call(int a) {
  return a + 1;
}

int intermediate(int a) {
  return call(a);
}
}

int main() {
  int foo = 42;
  foo = 3;
  Test t();
  t.intermediate(foo);
}
