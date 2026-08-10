Name:           keyguard
Version:        %{_app_version}
Release:        1%{?dist}
Summary:        Password manager
License:        Proprietary
URL:            https://github.com/AChep/keyguard-app
Source0:        %{name}-%{version}.tar.gz

%description
Keyguard is a cross-platform password manager that supports Bitwarden
and KeePass databases.

%prep
%setup -q -n Keyguard

%install
mkdir -p %{buildroot}/opt/%{name}
cp -a * %{buildroot}/opt/%{name}/

# The launcher may arrive with a normalized non-executable mode
# because Gradle's Tar task does not preserve file permissions.
chmod +x %{buildroot}/opt/%{name}/bin/Keyguard

mkdir -p %{buildroot}/usr/bin
ln -sf /opt/%{name}/bin/Keyguard %{buildroot}/usr/bin/%{name}

mkdir -p %{buildroot}/usr/share/applications
cat > %{buildroot}/usr/share/applications/%{name}.desktop << 'EOF'
[Desktop Entry]
Name=Keyguard
Comment=Password manager
Exec=keyguard
Terminal=false
Type=Application
Icon=com.artemchep.keyguard
Categories=Utility;
EOF

mkdir -p %{buildroot}/usr/share/icons/hicolor/scalable/apps
cp share/icons/hicolor/scalable/apps/com.artemchep.keyguard.svg \
   %{buildroot}/usr/share/icons/hicolor/scalable/apps/ 2>/dev/null || true

mkdir -p %{buildroot}/usr/share/metainfo
cp share/metainfo/com.artemchep.keyguard.metainfo.xml \
   %{buildroot}/usr/share/metainfo/ 2>/dev/null || true

%files
/opt/%{name}/
/usr/bin/%{name}
/usr/share/applications/%{name}.desktop
/usr/share/icons/hicolor/scalable/apps/com.artemchep.keyguard.svg
/usr/share/metainfo/com.artemchep.keyguard.metainfo.xml

%changelog
