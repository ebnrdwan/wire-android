package com.waz.zclient.user.data.source.remote

import com.waz.zclient.core.exception.Failure
import com.waz.zclient.core.functional.Either
import com.waz.zclient.core.network.requestApi
import com.waz.zclient.user.data.source.remote.model.UserApi


class UsersRemoteDataSource constructor(private val usersNetworkService: UsersNetworkService) {

    suspend fun profile(): Either<Failure, UserApi> = requestApi { usersNetworkService.profile() }

    suspend fun changeName(value: String): Either<Failure, Void> = requestApi { usersNetworkService.changeName(UserApi(name=value))}

    suspend fun changeHandle(value: String): Either<Failure, Void> = requestApi { usersNetworkService.changeHandle(value) }

    suspend fun changeEmail(value: String): Either<Failure, Void> = requestApi { usersNetworkService.changeEmail(value) }

    suspend fun changePhone(value: String): Either<Failure, Void> = requestApi { usersNetworkService.changePhone(value) }
}
