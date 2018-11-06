# Copyright (c) 2017-2018 HERE Europe B.V.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# SPDX-License-Identifier: Apache-2.0
# License-Filename: LICENSE

FROM openjdk:jre-alpine

ENV SCANCODE_VERSION=sys-platform-startswith

# Add VCS tools (no specific versions required here).
RUN apk add --no-cache \
        cvs \
        git \
        mercurial \
        subversion \
    && \

    wget -O /usr/local/bin/repo https://storage.googleapis.com/git-repo-downloads/repo && \
    chmod a+x /usr/local/bin/repo

# Add package managers (in versions known to work).
RUN apk add --no-cache --repository http://nl.alpinelinux.org/alpine/edge/testing \
        composer=1.6.5-r0 \
        # This is actually the node version; it will install npm 5.6.0:
        npm=8.11.4-r0 \
        py2-pip=10.0.1-r0 \
        ruby-bundler=1.16.2-r1 \
        # From the "testing" repository; this is actually the sbt *launcher* version:
        sbt=1.1.5-r0 \
    && \

    npm install --global bower@1.8.4 yarn@1.9.4 && \
    pip install virtualenv==15.1.0 && \

    wget -q -O - https://raw.githubusercontent.com/golang/dep/7d5cd199ce454707f81c63b7ea4299151b8b981d/install.sh | \
        DEP_RELEASE_TAG="v0.4.1" GOPATH=/tmp/gopath INSTALL_DIRECTORY=/usr/local/bin sh && \

    wget -q -O - https://get.haskellstack.org/ | sh

# Add scanners (in versions known to work).
RUN wget -q -O - https://github.com/nexB/scancode-toolkit/archive/$SCANCODE_VERSION.tar.gz | \
        tar -zxC /usr/local && \
        # Trigger configuration for end-users:
        /usr/local/scancode-toolkit-$SCANCODE_VERSION/scancode --version && \
        ln -s /usr/local/scancode-toolkit-$SCANCODE_VERSION/scancode /usr/local/bin/scancode
