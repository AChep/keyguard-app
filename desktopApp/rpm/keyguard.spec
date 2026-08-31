Name:           keyguard
Version:        %{_app_version}
Release:        1%{?dist}
Summary:        Password manager
License:        Proprietary
URL:            https://github.com/AChep/keyguard-app
Source0:        %{name}-%{version}.tar.gz
Requires:       /usr/bin/xdg-open

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

install -Dm0644 \
  share/applications/com.artemchep.keyguard.desktop \
  %{buildroot}%{_datadir}/applications/com.artemchep.keyguard.desktop
sed -i 's/^Exec=Keyguard$/Exec=keyguard/' \
  %{buildroot}%{_datadir}/applications/com.artemchep.keyguard.desktop

install -Dm0644 \
  share/icons/hicolor/scalable/apps/com.artemchep.keyguard.svg \
  %{buildroot}%{_datadir}/icons/hicolor/scalable/apps/com.artemchep.keyguard.svg
install -Dm0644 \
  share/metainfo/com.artemchep.keyguard.metainfo.xml \
  %{buildroot}%{_datadir}/metainfo/com.artemchep.keyguard.metainfo.xml

%files
/opt/%{name}/
/usr/bin/%{name}
%{_datadir}/applications/com.artemchep.keyguard.desktop
%{_datadir}/icons/hicolor/scalable/apps/com.artemchep.keyguard.svg
%{_datadir}/metainfo/com.artemchep.keyguard.metainfo.xml

%changelog
