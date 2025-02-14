# base recipe: meta/recipes-devtools/python/python3_3.7.4.bb
# base branch: warrior
# base commit: cd8a048b620b04dcaa7969d70684bcad243ccc58

SUMMARY = "The Python Programming Language"
HOMEPAGE = "http://www.python.org"

LICENSE = "PSFv2"
LIC_FILES_CHKSUM = "file://LICENSE;md5=e466242989bd33c1bd2b6a526a742498"

inherit debian-package
require recipes-debian/sources/python3.7.inc
DEBIAN_UNPACK_DIR = "${WORKDIR}/Python-${PV}"

BASE_RECIPE_DIR = "${COREBASE}/meta/recipes-devtools/python"
FILESPATH_append = ":${BASE_RECIPE_DIR}/python3"
SRC_URI += " \
    file://run-ptest \
    file://create_manifest3.py \
    file://get_module_deps3.py \
    file://python3-manifest.json \
    file://check_build_completeness.py \
    file://cgi_py.patch \
    file://0001-Do-not-add-usr-lib-termcap-to-linker-flags-to-avoid-.patch \
    ${@bb.utils.contains('PACKAGECONFIG', 'tk', '', 'file://avoid_warning_about_tkinter.patch', d)} \
    file://0001-Do-not-use-the-shell-version-of-python-config-that-w.patch \
    file://python-config.patch \
    file://0001-Makefile.pre-use-qemu-wrapper-when-gathering-profile.patch \
    file://0001-Do-not-hardcode-lib-as-location-for-site-packages-an.patch \
    file://0001-python3-use-cc_basename-to-replace-CC-for-checking-c.patch \
    file://0002-Don-t-do-runtime-test-to-get-float-byte-order.patch \
    file://0001-Lib-sysconfig.py-fix-another-place-where-lib-is-hard.patch \
    file://restore_site-packages_in_sitepackages_paths.diff \
    file://0020-configure.ac-setup.py-do-not-add-a-curses-include-pa.patch \
"

SRC_URI_append_class-native = " \
    file://0001-distutils-sysconfig-append-STAGING_LIBDIR-python-sys.patch \
    file://12-distutils-prefix-is-inside-staging-area.patch \
"
SRC_URI_append_class-nativesdk = " \
    file://0001-main.c-if-OEPYTHON3HOME-is-set-use-instead-of-PYTHON.patch \
"

PYTHON_MAJMIN = "3.7"
PYTHON_BINABI = "${PYTHON_MAJMIN}m"

do_debian_patch_prepend() {
	# Debian puts pkgconfig in /usr/lib/$(MULTIARCH)/pkgconfig
	# which is different with Poky's multiarch structure
	sed -i -e "/^multiarch*.diff/d" ${DEBIAN_UNPACK_DIR}/debian/patches/series

	# Use qemu-wrapper instead, so it can be cross built
	sed -i -e "/^profiled-build.diff/d" ${DEBIAN_UNPACK_DIR}/debian/patches/series

        # The expat package version does not match, so commented out to exclude patches that for modify the test code.
        # '0003-bpo-46811-Make-test-suite-support-Expat-2.4.5-GH-314.patch' requires libexpat >= 2.4.5
       sed -i -e "/^0003-bpo-46811-Make-test-suite-support-Expat-2.4.5-GH-314.patch/s/^/#/" ${DEBIAN_UNPACK_DIR}/debian/patches/series
        # '0004-3.8-gh-115133-Fix-tests-for-XMLPullParser-with-Expat.patch' requires expat 2.6
       sed -i -e "/^0004-3.8-gh-115133-Fix-tests-for-XMLPullParser-with-Expat.patch/s/^/#/" ${DEBIAN_UNPACK_DIR}/debian/patches/series
}

inherit autotools pkgconfig qemu ptest multilib_header update-alternatives

MULTILIB_SUFFIX = "${@d.getVar('base_libdir',1).split('/')[-1]}"

ALTERNATIVE_${PN}-dev = "python-config"
ALTERNATIVE_LINK_NAME[python-config] = "${bindir}/python${PYTHON_BINABI}-config"
ALTERNATIVE_TARGET[python-config] = "${bindir}/python${PYTHON_BINABI}-config-${MULTILIB_SUFFIX}"


DEPENDS = "bzip2-replacement-native libffi bzip2 gdbm openssl sqlite3 zlib virtual/libintl xz virtual/crypt util-linux libtirpc libnsl2"
DEPENDS_append_class-target = " python3-native"
DEPENDS_append_class-nativesdk = " python3-native"

EXTRA_OECONF = " --without-ensurepip --enable-shared"
EXTRA_OECONF_append_class-native = " --bindir=${bindir}/${PN}"


EXTRANATIVEPATH += "python3-native"

CACHED_CONFIGUREVARS = " \
    ac_cv_file__dev_ptmx=yes \
    ac_cv_file__dev_ptc=no \
    ac_cv_working_tzset=yes \
"

PACKAGECONFIG_class-target ??= "readline ${@bb.utils.contains('MACHINE_FEATURES', 'qemu-usermode', 'pgo', '', d)}"
PACKAGECONFIG_class-native ??= "readline"
PACKAGECONFIG_class-nativesdk ??= "readline"
PACKAGECONFIG[readline] = ",,readline"
# Use profile guided optimisation by running PyBench inside qemu-user
PACKAGECONFIG[pgo] = "--enable-optimizations,,qemu-native"
PACKAGECONFIG[tk] = ",,tk"

CPPFLAGS_append = " -I${STAGING_INCDIR}/ncursesw -I${STAGING_INCDIR}/uuid"

EXTRA_OEMAKE = '\
    STAGING_LIBDIR=${STAGING_LIBDIR} \
    STAGING_INCDIR=${STAGING_INCDIR} \
    LIB=${baselib} \
'

do_compile_prepend_class-target() {
	if ${@bb.utils.contains('PACKAGECONFIG', 'pgo', 'true', 'false', d)}; then
		qemu_binary="${@qemu_wrapper_cmdline(d, '${STAGING_DIR_TARGET}', ['${B}', '${STAGING_DIR_TARGET}/${base_libdir}'])}"
		cat >pgo-wrapper <<EOF
#!/bin/sh
cd ${B}
$qemu_binary "\$@"
EOF
		chmod +x pgo-wrapper
	fi
}

do_install_prepend() {
	${WORKDIR}/check_build_completeness.py ${T}/log.do_compile
}

do_install_append_class-target() {
	oe_multilib_header python${PYTHON_BINABI}/pyconfig.h
}

do_install_append_class-native() {
	# Make sure we use /usr/bin/env python
	for PYTHSCRIPT in `grep -rIl ${bindir}/${PN}/python ${D}${bindir}/${PN}`; do
		sed -i -e '1s|^#!.*|#!/usr/bin/env python3|' $PYTHSCRIPT
	done
	# Add a symlink to the native Python so that scripts can just invoke
	# "nativepython" and get the right one without needing absolute paths
	# (these often end up too long for the #! parser in the kernel as the
	# buffer is 128 bytes long).
	ln -s python3-native/python3 ${D}${bindir}/nativepython3
}

do_install_append() {
	mkdir -p ${D}${libdir}/python-sysconfigdata
	sysconfigfile=`find ${D} -name _sysconfig*.py`
	cp $sysconfigfile ${D}${libdir}/python-sysconfigdata/_sysconfigdata.py

	sed -i  \
	    -e "s,^ 'LIBDIR'.*, 'LIBDIR': '${STAGING_LIBDIR}'\,,g" \
	    -e "s,^ 'INCLUDEDIR'.*, 'INCLUDEDIR': '${STAGING_INCDIR}'\,,g" \
	    -e "s,^ 'CONFINCLUDEDIR'.*, 'CONFINCLUDEDIR': '${STAGING_INCDIR}'\,,g" \
	    -e "/^ 'INCLDIRSTOMAKE'/{N; s,/usr/include,${STAGING_INCDIR},g}" \
	    -e "/^ 'INCLUDEPY'/s,/usr/include,${STAGING_INCDIR},g" \
	    ${D}${libdir}/python-sysconfigdata/_sysconfigdata.py
}

do_install_append_class-nativesdk () {
    create_wrapper ${D}${bindir}/python${PYTHON_MAJMIN} OEPYTHON3HOME='${prefix}' TERMINFO_DIRS='${sysconfdir}/terminfo:/etc/terminfo:/usr/share/terminfo:/usr/share/misc/terminfo:/lib/terminfo' PYTHONNOUSERSITE='1'
}

SSTATE_SCAN_FILES += "Makefile _sysconfigdata.py"
PACKAGE_PREPROCESS_FUNCS += "py_package_preprocess"

py_package_preprocess () {
	# Remove references to buildmachine paths in target Makefile and _sysconfigdata
	sed -i -e 's:--sysroot=${STAGING_DIR_TARGET}::g' -e s:'--with-libtool-sysroot=${STAGING_DIR_TARGET}'::g \
	    -e 's|${DEBUG_PREFIX_MAP}||g' \
	    -e 's:${HOSTTOOLS_DIR}/::g' \
	    -e 's:${RECIPE_SYSROOT_NATIVE}::g' \
	    -e 's:${RECIPE_SYSROOT}::g' \
	    -e 's:${BASE_WORKDIR}/${MULTIMACH_TARGET_SYS}::g' \
	    ${PKGD}/${prefix}/lib/python${PYTHON_MAJMIN}/config-${PYTHON_MAJMIN}${PYTHON_ABI}*/Makefile \
	    ${PKGD}/${libdir}/python${PYTHON_MAJMIN}/_sysconfigdata*.py \
	    ${PKGD}/${bindir}/python${PYTHON_BINABI}-config

	# Recompile _sysconfigdata after modifying it
	cd ${PKGD}
	sysconfigfile=`find . -name _sysconfigdata_*.py`
	${STAGING_BINDIR_NATIVE}/python3-native/python3 \
	     -c "from py_compile import compile; compile('$sysconfigfile')"
	${STAGING_BINDIR_NATIVE}/python3-native/python3 \
	     -c "from py_compile import compile; compile('$sysconfigfile', optimize=1)"
	${STAGING_BINDIR_NATIVE}/python3-native/python3 \
	     -c "from py_compile import compile; compile('$sysconfigfile', optimize=2)"
	cd -

	mv ${PKGD}/${bindir}/python${PYTHON_BINABI}-config ${PKGD}/${bindir}/python${PYTHON_BINABI}-config-${MULTILIB_SUFFIX}

	#Remove the unneeded copy of target sysconfig data
	rm -rf ${PKGD}/${libdir}/python-sysconfigdata
}

# We want bytecode precompiled .py files (.pyc's) by default
# but the user may set it on their own conf
INCLUDE_PYCS ?= "1"

python(){
    import collections, json

    filename = os.path.join(d.getVar('BASE_RECIPE_DIR'), 'python3', 'python3-manifest.json')
    # This python changes the datastore based on the contents of a file, so mark
    # that dependency.
    bb.parse.mark_dependency(d, filename)

    with open(filename) as manifest_file:
        manifest_str =  manifest_file.read()
        json_start = manifest_str.find('# EOC') + 6
        manifest_file.seek(json_start)
        manifest_str = manifest_file.read()
        python_manifest = json.loads(manifest_str, object_pairs_hook=collections.OrderedDict)

    # First set RPROVIDES for -native case
    # Hardcoded since it cant be python3-native-foo, should be python3-foo-native
    pn = 'python3'
    rprovides = d.getVar('RPROVIDES').split()

    for key in python_manifest:
        pypackage = pn + '-' + key + '-native'
        if pypackage not in rprovides:
              rprovides.append(pypackage)

    d.setVar('RPROVIDES_class-native', ' '.join(rprovides))

    # Then work on the target
    include_pycs = d.getVar('INCLUDE_PYCS')

    packages = d.getVar('PACKAGES').split()
    pn = d.getVar('PN')

    newpackages=[]
    for key in python_manifest:
        pypackage= pn + '-' + key

        if pypackage not in packages:
            # We need to prepend, otherwise python-misc gets everything
            # so we use a new variable
            newpackages.append(pypackage)

        # "Build" python's manifest FILES, RDEPENDS and SUMMARY
        d.setVar('FILES_' + pypackage, '')
        for value in python_manifest[key]['files']:
            d.appendVar('FILES_' + pypackage, ' ' + value)

        # Add cached files
        if include_pycs == '1':
            for value in python_manifest[key]['cached']:
                    d.appendVar('FILES_' + pypackage, ' ' + value)

        for value in python_manifest[key]['rdepends']:
            # Make it work with or without $PN
            if '${PN}' in value:
                value=value.split('-')[1]
            d.appendVar('RDEPENDS_' + pypackage, ' ' + pn + '-' + value)
        d.setVar('SUMMARY_' + pypackage, python_manifest[key]['summary'])

    # Prepending so to avoid python-misc getting everything
    packages = newpackages + packages
    d.setVar('PACKAGES', ' '.join(packages))
    d.setVar('ALLOW_EMPTY_${PN}-modules', '1')
}

# Files needed to create a new manifest

do_create_manifest() {
	# This task should be run with every new release of Python.
	# We must ensure that PACKAGECONFIG enables everything when creating
	# a new manifest, this is to base our new manifest on a complete
	# native python build, containing all dependencies, otherwise the task
	# wont be able to find the required files.
	# e.g. BerkeleyDB is an optional build dependency so it may or may not
	# be present, we must ensure it is.

	cd ${WORKDIR}
	# This needs to be executed by python-native and NOT by HOST's python
	nativepython3 create_manifest3.py ${PYTHON_MAJMIN}
	cp python3-manifest.json.new ${THISDIR}/python3/python3-manifest.json
}

# bitbake python -c create_manifest
addtask do_create_manifest

# Make sure we have native python ready when we create a new manifest
do_create_manifest[depends] += "${PN}:do_prepare_recipe_sysroot"
do_create_manifest[depends] += "${PN}:do_patch"

# manual dependency additions
RPROVIDES_${PN}-modules = "${PN}"
RRECOMMENDS_${PN}-core_append_class-nativesdk = " nativesdk-python3-modules"
RRECOMMENDS_${PN}-crypt_append_class-target = " openssl ca-certificates"
RRECOMMENDS_${PN}-crypt_append_class-nativesdk = " openssl ca-certificates"

FILES_${PN}-pydoc += "${bindir}/pydoc${PYTHON_MAJMIN} ${bindir}/pydoc3"
FILES_${PN}-idle += "${bindir}/idle3 ${bindir}/idle${PYTHON_MAJMIN}"

# provide python-pyvenv from python3-venv
RPROVIDES_${PN}-venv += "python3-pyvenv"

# package libpython3
PACKAGES =+ "libpython3 libpython3-staticdev"
FILES_libpython3 = "${libdir}/libpython*.so.*"
FILES_libpython3-staticdev += "${prefix}/lib/python${PYTHON_MAJMIN}/config-${PYTHON_BINABI}-*/libpython${PYTHON_BINABI}.a"
INSANE_SKIP_${PN}-dev += "dev-elf"

# catch all the rest (unsorted)
PACKAGES += "${PN}-misc"
RDEPENDS_${PN}-misc += "python3-core python3-email python3-codecs"
RDEPENDS_${PN}-modules_append_class-target = " python3-misc"
RDEPENDS_${PN}-modules_append_class-nativesdk = " python3-misc"
FILES_${PN}-misc = "${libdir}/python${PYTHON_MAJMIN} ${libdir}/python${PYTHON_MAJMIN}/lib-dynload"

# catch manpage
PACKAGES += "${PN}-man"
FILES_${PN}-man = "${datadir}/man"

RDEPENDS_${PN}-ptest = "${PN}-modules ${PN}-tests unzip bzip2 libgcc tzdata-europe coreutils sed netbase"
RDEPENDS_${PN}-ptest_append_libc-glibc = " locale-base-tr-tr.iso-8859-9"
RDEPENDS_${PN}-tkinter += "${@bb.utils.contains('PACKAGECONFIG', 'tk', 'tk tk-lib', '', d)}"
RDEPENDS_${PN}-dev = ""

BBCLASSEXTEND = "native nativesdk"
