class Mine(Exception):
    pass


raise BaseExceptionGroup("base", [KeyboardInterrupt("ki"), Mine("m"), ValueError("v")])
