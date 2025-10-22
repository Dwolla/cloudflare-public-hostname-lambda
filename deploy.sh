#!/usr/bin/env bash
set -o errexit
IFS=$'\n\t'

# the .git folder and .gitignore file are not stashed by Jenkins, so we need to fetch them
git init
git remote add origin "$GIT_URL"
git fetch origin

set +o nounset
if [ -z ${TAG_NAME+x} ]; then
  TARGET_COMMIT="${GIT_COMMIT}"
else
  TARGET_COMMIT="${TAG_NAME}"
fi
readonly TARGET_COMMIT

# use `git checkout --force` here because we expect the working directory not to be
# empty at this point. Jenkins unstashed everything from the previous stage into the
# working directory; we want to keep the build artifacts (i.e. everything in the
# various target/ directories) but update the files committed to git to the version
# currently being built.
git checkout --force "${TARGET_COMMIT}"

# nvm is a bash function, so fake command echoing for nvm commands to reduce noise
echo "+ . ${NVM_DIR}/nvm.sh --no-use"
. "${NVM_DIR}/nvm.sh" --no-use

echo "+ nvm install"
nvm install

echo "+ export SDKMAN_DIR=$HOME/.sdkman"
export SDKMAN_DIR="$HOME/.sdkman"

# it seems like a bug in sdkman that this is needed 🤷
echo "+ mkdir -p ${SDKMAN_DIR}/candidates/java/current/bin"
mkdir -p "${SDKMAN_DIR}/candidates/java/current/bin"

echo "+ . ${SDKMAN_DIR}/bin/sdkman-init.sh"
. "${SDKMAN_DIR}/bin/sdkman-init.sh"

echo "+ sdk env install use"
sdk env install use

set -o xtrace -o nounset -o pipefail
npm install -g npm
npm install -g serverless

sbt "show deploy Admin"
