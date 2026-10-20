# A class written in the script is named by its qualified name, without its module.
class Mine(ValueError):
    def __init__(self, code):
        super().__init__('code %d' % code)
        self.code = code

    def __str__(self):
        return 'Mine(code=%d)' % self.code

class Nested:
    class Deeper(Exception):
        pass

def run():
    raise Mine(3)

try:
    run()
except Mine:
    print('caught mine')
raise Nested.Deeper('qualified')
