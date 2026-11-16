var 1..3: x;
var -5..3: objective = 7 - 4*x;
constraint x >= 2;
solve minimize objective;
