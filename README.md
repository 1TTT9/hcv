HCVService

2013-01-16: init, damn it...

2013-01-21: meet difficulty to use writerequested() correctly. 
Those examples on the net are used to send messages immediately; through our 
app requires one channel to wait for all messsages collected from the other
 clients, compute them together and thus send them back.
Still working on it...

2013-01-23: Today I still didn't find a way to write back the message 
gracefully without knowing how to pass write-backed message to workers 
controlled by netty itself. Damn it... 


