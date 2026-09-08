FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update \
 && apt-get install -y --no-install-recommends openssh-server sudo iproute2 procps ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && mkdir -p /var/run/sshd \
 && useradd -m -s /bin/bash lab \
 && echo 'lab ALL=(ALL) NOPASSWD:ALL' > /etc/sudoers.d/lab \
 && chmod 440 /etc/sudoers.d/lab \
 && mkdir -p /home/lab/.ssh \
 && chmod 700 /home/lab/.ssh

COPY keys/lab.pub /home/lab/.ssh/authorized_keys

RUN chown -R lab:lab /home/lab/.ssh \
 && chmod 600 /home/lab/.ssh/authorized_keys \
 && sed -i 's/#PasswordAuthentication yes/PasswordAuthentication no/' /etc/ssh/sshd_config \
 && sed -i 's/#PubkeyAuthentication yes/PubkeyAuthentication yes/' /etc/ssh/sshd_config

EXPOSE 22
CMD ["/usr/sbin/sshd", "-D", "-e"]
