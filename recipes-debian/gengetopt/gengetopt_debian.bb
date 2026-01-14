
SUMMARY = "skeleton main.c generator"
DESCRIPTION = "Gengetopt is a tool to write command line option parsing code for C programs."
SECTION = "utils"
HOMEPAGE = "https://www.gnu.org/software/gengetopt/gengetopt.html"

inherit debian-package
require recipes-debian/sources/gengetopt.inc

LICENSE = "GPLv3+"
LIC_FILES_CHKSUM = "file://COPYING;md5=ff95bfe019feaf92f524b73dd79e76eb"

inherit autotools

BBCLASSEXTEND = "native nativesdk"

do_unpack_append() {
    bb.build.exec_func('do_unpack_extra', d)
}

do_unpack_extra() {
    if [ -d "${S}+dfsg0.orig" ]; then
        mv ${S}+dfsg0.orig/* ${S}/
        rm -fr ${S}+dfsg0.orig/
    fi
}
