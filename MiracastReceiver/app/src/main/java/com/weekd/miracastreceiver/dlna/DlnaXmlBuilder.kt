package com.weekd.miracastreceiver.dlna

/**
 * DLNA/UPnP XML 生成器
 */
object DlnaXmlBuilder {

    fun buildDeviceDescription(
        deviceUuid: String,
        deviceName: String,
        manufacturer: String,
        modelName: String,
        baseUrl: String
    ): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0">
  <specVersion>
    <major>1</major>
    <minor>0</minor>
  </specVersion>
  <URLBase>$baseUrl</URLBase>
  <device>
    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
    <friendlyName>$deviceName</friendlyName>
    <manufacturer>$manufacturer</manufacturer>
    <manufacturerURL>https://github.com</manufacturerURL>
    <modelDescription>Android TV DLNA MediaRenderer</modelDescription>
    <modelName>$modelName</modelName>
    <modelNumber>1.0</modelNumber>
    <modelURL>https://github.com</modelURL>
    <serialNumber>$deviceUuid</serialNumber>
    <UDN>uuid:$deviceUuid</UDN>
    <dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC>
    <dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>
    <serviceList>
      <service>
        <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId>
        <SCPDURL>/service/ConnectionManager.xml</SCPDURL>
        <controlURL>/control/ConnectionManager</controlURL>
        <eventSubURL>/event/ConnectionManager</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:AVTransport</serviceId>
        <SCPDURL>/service/AVTransport.xml</SCPDURL>
        <controlURL>/control/AVTransport</controlURL>
        <eventSubURL>/event/AVTransport</eventSubURL>
      </service>
      <service>
        <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
        <serviceId>urn:upnp-org:serviceId:RenderingControl</serviceId>
        <SCPDURL>/service/RenderingControl.xml</SCPDURL>
        <controlURL>/control/RenderingControl</controlURL>
        <eventSubURL>/event/RenderingControl</eventSubURL>
      </service>
    </serviceList>
  </device>
</root>"""
    }

    fun buildConnectionManagerScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action>
      <name>GetProtocolInfo</name>
      <argumentList>
        <argument><name>Source</name><direction>out</direction><relatedStateVariable>SourceProtocolInfo</relatedStateVariable></argument>
        <argument><name>Sink</name><direction>out</direction><relatedStateVariable>SinkProtocolInfo</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetCurrentConnectionIDs</name>
      <argumentList>
        <argument><name>ConnectionIDs</name><direction>out</direction><relatedStateVariable>CurrentConnectionIDs</relatedStateVariable></argument>
      </argumentList>
    </action>
    <action>
      <name>GetCurrentConnectionInfo</name>
      <argumentList>
        <argument><name>ConnectionID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>RcsID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_RcsID</relatedStateVariable></argument>
        <argument><name>AVTransportID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_AVTransportID</relatedStateVariable></argument>
        <argument><name>ProtocolInfo</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ProtocolInfo</relatedStateVariable></argument>
        <argument><name>PeerConnectionManager</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionManager</relatedStateVariable></argument>
        <argument><name>PeerConnectionID</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionID</relatedStateVariable></argument>
        <argument><name>Direction</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Direction</relatedStateVariable></argument>
        <argument><name>Status</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_ConnectionStatus</relatedStateVariable></argument>
      </argumentList>
    </action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="yes"><name>SourceProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>SinkProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentConnectionIDs</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_RcsID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_AVTransportID</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ProtocolInfo</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionManager</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Direction</name><dataType>string</dataType><allowedValueList><allowedValue>Input</allowedValue><allowedValue>Output</allowedValue></allowedValueList></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_ConnectionStatus</name><dataType>string</dataType><allowedValueList><allowedValue>OK</allowedValue><allowedValue>ContentFormatMismatch</allowedValue><allowedValue>InsufficientBandwidth</allowedValue><allowedValue>UnreliableChannel</allowedValue><allowedValue>Unknown</allowedValue></allowedValueList></stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    fun buildAvTransportScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>SetAVTransportURI</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>CurrentURI</name><direction>in</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument><argument><name>CurrentURIMetaData</name><direction>in</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument></argumentList></action>
    <action><name>Play</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Speed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>SetRate</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>DesiredRate</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>SetSpeed</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>DesiredSpeed</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>X_SetRate</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Rate</name><direction>in</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>Pause</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
    <action><name>Stop</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument></argumentList></action>
    <action><name>Seek</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument><argument><name>Target</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekTarget</relatedStateVariable></argument></argumentList></action>
    <action><name>GetTransportInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>CurrentTransportState</name><direction>out</direction><relatedStateVariable>TransportState</relatedStateVariable></argument><argument><name>CurrentTransportStatus</name><direction>out</direction><relatedStateVariable>TransportStatus</relatedStateVariable></argument><argument><name>CurrentSpeed</name><direction>out</direction><relatedStateVariable>TransportPlaySpeed</relatedStateVariable></argument></argumentList></action>
    <action><name>GetPositionInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Track</name><direction>out</direction><relatedStateVariable>CurrentTrack</relatedStateVariable></argument><argument><name>TrackDuration</name><direction>out</direction><relatedStateVariable>CurrentTrackDuration</relatedStateVariable></argument><argument><name>TrackMetaData</name><direction>out</direction><relatedStateVariable>CurrentTrackMetaData</relatedStateVariable></argument><argument><name>TrackURI</name><direction>out</direction><relatedStateVariable>CurrentTrackURI</relatedStateVariable></argument><argument><name>RelTime</name><direction>out</direction><relatedStateVariable>RelativeTimePosition</relatedStateVariable></argument><argument><name>AbsTime</name><direction>out</direction><relatedStateVariable>AbsoluteTimePosition</relatedStateVariable></argument><argument><name>RelCount</name><direction>out</direction><relatedStateVariable>RelativeCounterPosition</relatedStateVariable></argument><argument><name>AbsCount</name><direction>out</direction><relatedStateVariable>AbsoluteCounterPosition</relatedStateVariable></argument></argumentList></action>
    <action><name>GetMediaInfo</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>NrTracks</name><direction>out</direction><relatedStateVariable>NumberOfTracks</relatedStateVariable></argument><argument><name>MediaDuration</name><direction>out</direction><relatedStateVariable>CurrentMediaDuration</relatedStateVariable></argument><argument><name>CurrentURI</name><direction>out</direction><relatedStateVariable>AVTransportURI</relatedStateVariable></argument><argument><name>CurrentURIMetaData</name><direction>out</direction><relatedStateVariable>AVTransportURIMetaData</relatedStateVariable></argument><argument><name>NextURI</name><direction>out</direction><relatedStateVariable>NextAVTransportURI</relatedStateVariable></argument><argument><name>NextURIMetaData</name><direction>out</direction><relatedStateVariable>NextAVTransportURIMetaData</relatedStateVariable></argument><argument><name>PlayMedium</name><direction>out</direction><relatedStateVariable>PlaybackStorageMedium</relatedStateVariable></argument><argument><name>RecordMedium</name><direction>out</direction><relatedStateVariable>RecordStorageMedium</relatedStateVariable></argument><argument><name>WriteStatus</name><direction>out</direction><relatedStateVariable>RecordMediumWriteStatus</relatedStateVariable></argument></argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_SeekTarget</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportState</name><dataType>string</dataType><defaultValue>STOPPED</defaultValue></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportStatus</name><dataType>string</dataType><defaultValue>OK</defaultValue></stateVariable>
    <stateVariable sendEvents="yes"><name>TransportPlaySpeed</name><dataType>string</dataType><defaultValue>1</defaultValue></stateVariable>
    <stateVariable sendEvents="yes"><name>AVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>AVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentTrackURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentTrackMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentTrack</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentTrackDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>CurrentMediaDuration</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>RelativeTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>AbsoluteTimePosition</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>RelativeCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>AbsoluteCounterPosition</name><dataType>i4</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>NumberOfTracks</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>NextAVTransportURI</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>NextAVTransportURIMetaData</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>PlaybackStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>RecordStorageMedium</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>RecordMediumWriteStatus</name><dataType>string</dataType></stateVariable>
  </serviceStateTable>
</scpd>"""
    }

    fun buildRenderingControlScpd(): String {
        return """<?xml version="1.0" encoding="utf-8"?>
<scpd xmlns="urn:schemas-upnp-org:service-1-0">
  <specVersion><major>1</major><minor>0</minor></specVersion>
  <actionList>
    <action><name>GetVolume</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>CurrentVolume</name><direction>out</direction><relatedStateVariable>Volume</relatedStateVariable></argument></argumentList></action>
    <action><name>SetVolume</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>DesiredVolume</name><direction>in</direction><relatedStateVariable>Volume</relatedStateVariable></argument></argumentList></action>
    <action><name>GetMute</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>CurrentMute</name><direction>out</direction><relatedStateVariable>Mute</relatedStateVariable></argument></argumentList></action>
    <action><name>SetMute</name><argumentList><argument><name>InstanceID</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_InstanceID</relatedStateVariable></argument><argument><name>Channel</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_Channel</relatedStateVariable></argument><argument><name>DesiredMute</name><direction>in</direction><relatedStateVariable>Mute</relatedStateVariable></argument></argumentList></action>
  </actionList>
  <serviceStateTable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_InstanceID</name><dataType>ui4</dataType></stateVariable>
    <stateVariable sendEvents="no"><name>A_ARG_TYPE_Channel</name><dataType>string</dataType></stateVariable>
    <stateVariable sendEvents="yes"><name>Volume</name><dataType>ui2</dataType><defaultValue>50</defaultValue><allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange></stateVariable>
    <stateVariable sendEvents="yes"><name>Mute</name><dataType>boolean</dataType><defaultValue>0</defaultValue></stateVariable>
  </serviceStateTable>
</scpd>"""
    }
}
