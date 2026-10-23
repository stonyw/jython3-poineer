# The same miss on the class rather than on one of its values: the members read are the metatype's view
# of the class, which is what the failed read was looking through.
class Thing:
    value = 2


Thing.valu
