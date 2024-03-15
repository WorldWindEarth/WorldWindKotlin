package earth.worldwind.util.ormlite

import com.j256.ormlite.support.ConnectionSource

expect fun initConnection(pathName: String, readOnly: Boolean): ConnectionSource