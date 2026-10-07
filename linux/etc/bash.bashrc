# The guest's login profile (S5 initramfs)
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
export TERM=${TERM:-linux}
export HOME=/root
export PS1='\u@\h:\w\$ '
# a serial console does not know its size: ask the terminal at the other end of the socket
[ -x /bin/resize ] && eval "$(resize 2>/dev/null)" >/dev/null
alias ll='ls -l'
